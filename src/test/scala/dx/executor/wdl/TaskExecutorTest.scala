package dx.executor.wdl

import java.nio.file.{Files, Path, Paths}

import dx.Assumptions.isLoggedIn
import dx.Tags.EdgeTest
import dx.api.{DiskType, DxAnalysis, DxApi, DxInstanceType, DxJob, DxProject, InstanceTypeDB}
import dx.core.Native
import dx.core.io.{DxFileAccessProtocol, DxFileDescCache, DxWorkerPaths}
import dx.core.ir.{ParameterLink, ParameterLinkDeserializer, ParameterLinkSerializer}
import dx.core.languages.wdl.{Utils => WdlUtils}
import dx.core.util.CompressionUtils
import dx.executor.{JobMeta, TaskAction, TaskExecutor}
import dx.translator.wdl.CodeGenerator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import wdlTools.eval.WdlValues
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}
import wdlTools.util.{FileSourceResolver, JsUtils, Logger, SysUtils}

private case class TaskTestJobMeta(override val homeDir: Path = DxWorkerPaths.HomeDir,
                                   override val dxApi: DxApi = DxApi.get,
                                   override val logger: Logger = Logger.get,
                                   override val jsInputs: Map[String, JsValue],
                                   rawInstanceTypeDb: InstanceTypeDB,
                                   rawSourceCode: String)
    extends JobMeta(homeDir, dxApi, logger) {
  var outputs: Option[Map[String, JsValue]] = None

  override val project: DxProject = null

  override def writeJsOutputs(outputJs: Map[String, JsValue]): Unit = {
    outputs = Some(outputJs)
  }

  override val jobId: String = null

  override val analysis: Option[DxAnalysis] = None

  override val parentJob: Option[DxJob] = None

  override val instanceType: Option[String] = Some(TaskTestJobMeta.InstanceType)

  override def getJobDetail(name: String): Option[JsValue] = None

  private val executableDetails: Map[String, JsValue] = Map(
      Native.InstanceTypeDb -> JsString(
          CompressionUtils.gzipAndBase64Encode(
              rawInstanceTypeDb.toJson.prettyPrint
          )
      ),
      Native.SourceCode -> JsString(CompressionUtils.gzipAndBase64Encode(rawSourceCode))
  )

  override def getExecutableDetail(name: String): Option[JsValue] = {
    executableDetails.get(name)
  }

  override def error(e: Throwable): Unit = {}
}

private object TaskTestJobMeta {
  val InstanceType = "mem_ssd_unicorn"
}

// This test module requires being logged in to the platform.
// It compiles WDL scripts without the runtime library.
// This tests the compiler Native mode, however, it creates
// dnanexus applets and workflows that are not runnable.
class TaskExecutorTest extends AnyFlatSpec with Matchers {
  assume(isLoggedIn)
  private val logger = Logger.Quiet
  private val dxApi = DxApi(logger)
  private val unicornInstance = DxInstanceType(
      TaskTestJobMeta.InstanceType,
      100,
      100,
      4,
      gpu = false,
      Vector(("Ubuntu", "16.04")),
      Some(DiskType.SSD),
      Some(1.00f)
  )
  private val instanceTypeDB =
    InstanceTypeDB(Map(TaskTestJobMeta.InstanceType -> unicornInstance), pricingAvailable = true)

  // Note: if the file doesn't exist, this throws a null pointer exception
  private def pathFromBasename(basename: String): Option[Path] = {
    getClass.getResource(s"/task_runner/${basename}") match {
      case null => None
      case res  => Some(Paths.get(res.getPath))
    }
  }

  // Recursively go into a wdlValue, and add a base path to the file.
  // For example:
  //   foo.txt ---> /home/joe_heller/foo.txt
  //
  // This is used to convert relative paths to test files into absolute paths.
  // For example, convert:
  //  {
  //    "pattern" : "snow",
  //    "in_file" : "manuscript.txt"
  //  }
  // into:
  //  {
  //    "pattern" : "snow",
  //    "in_file" : "/home/joe_heller/dxWDL/src/test/resources/runner_tasks/manuscript.txt"
  // }
  //
  private def addBaseDir(wdlValue: WdlValues.V): WdlValues.V = {
    wdlValue match {
      // primitive types, pass through
      case WdlValues.V_Boolean(_) | WdlValues.V_Int(_) | WdlValues.V_Float(_) |
          WdlValues.V_String(_) | WdlValues.V_Null =>
        wdlValue

      // single file
      case WdlValues.V_File(s) =>
        pathFromBasename(s) match {
          case Some(path) =>
            WdlValues.V_File(path.toString)
          case None =>
            throw new Exception(s"File ${s} does not exist")
        }

      // Maps
      case WdlValues.V_Map(m: Map[WdlValues.V, WdlValues.V]) =>
        val m1 = m.map {
          case (k, v) =>
            val k1 = addBaseDir(k)
            val v1 = addBaseDir(v)
            k1 -> v1
        }
        WdlValues.V_Map(m1)

      case WdlValues.V_Pair(l, r) =>
        val left = addBaseDir(l)
        val right = addBaseDir(r)
        WdlValues.V_Pair(left, right)

      case WdlValues.V_Array(a: Seq[WdlValues.V]) =>
        val a1 = a.map { v =>
          addBaseDir(v)
        }
        WdlValues.V_Array(a1)

      case WdlValues.V_Optional(v) =>
        val v1 = addBaseDir(v)
        WdlValues.V_Optional(v1)

      case WdlValues.V_Object(m) =>
        val m2 = m.map {
          case (k, v) =>
            k -> addBaseDir(v)
        }
        WdlValues.V_Object(m2)

      case other =>
        throw new Exception(s"Unsupported wdl value ${other}")
    }
  }

  // Parse the WDL source code, and extract the single task that is supposed to be there.
  // Also return the source script itself, verbatim.
  private def runTask(wdlName: String): Unit = {
    val wdlFile: Path = pathFromBasename(s"${wdlName}.wdl").get
    val inputs: Map[String, JsValue] = pathFromBasename(s"${wdlName}_input.json") match {
      case Some(path) if Files.exists(path) => JsUtils.getFields(JsUtils.jsFromFile(path))
      case _                                => Map.empty
    }
    val outputsExpected: Option[Map[String, JsValue]] =
      pathFromBasename(s"${wdlName}_output.json") match {
        case Some(path) if Files.exists(path) => Some(JsUtils.getFields(JsUtils.jsFromFile(path)))
        case _                                => None
      }

    // Create a clean temp directory for the task to use
    val jobHomeDir: Path = Files.createTempDirectory("dxwdl_applet_test")
    jobHomeDir.toFile.deleteOnExit()
    val workerPaths = DxWorkerPaths(jobHomeDir)
    workerPaths.createCleanDirs()

    // create a stand-alone task
    val (doc, typeAliases) = WdlUtils.parseSourceFile(wdlFile)
    val codegen = CodeGenerator(typeAliases.bindings, doc.version.value, logger)
    val tasks: Vector[TAT.Task] = doc.elements.collect {
      case task: TAT.Task => task
    }
    tasks.size shouldBe 1
    val task = tasks.head
    val standAloneTask = codegen.standAloneTask(task)
    val standAloneTaskSource = codegen.generateDocument(standAloneTask)

    // update paths of input files - this requires a round-trip de/ser
    // JSON -> IR -> WDL -> update paths -> IR -> JSON
    // which requires lots of auxilliarly objects
    val dxFileDescCache = DxFileDescCache.empty
    val inputDeserializer: ParameterLinkDeserializer =
      ParameterLinkDeserializer(dxFileDescCache, dxApi)
    val dxProtocol = DxFileAccessProtocol(dxApi, dxFileDescCache)
    val fileResolver = FileSourceResolver.create(
        localDirectories = Vector(jobHomeDir),
        userProtocols = Vector(dxProtocol),
        logger = logger
    )
    val outputSerializer: ParameterLinkSerializer = ParameterLinkSerializer(fileResolver, dxApi)
    val taskInputs = task.inputs.map(inp => inp.name -> inp).toMap
    val updatedInputs = inputDeserializer
      .deserializeInputMap(inputs)
      .collect {
        case (name, irValue) if !name.endsWith(ParameterLink.FlatFilesSuffix) =>
          val wdlType = taskInputs(name).wdlType
          val wdlValue = addBaseDir(WdlUtils.fromIRValue(irValue, wdlType, name))
          val updatedIrValue = WdlUtils.toIRValue(wdlValue, wdlType)
          val irType = WdlUtils.toIRType(wdlType)
          outputSerializer.createFields(name, irType, updatedIrValue)
      }
      .flatten
      .toMap

    // create JobMeta
    val jobMeta =
      TaskTestJobMeta(jobHomeDir,
                      dxApi,
                      logger,
                      updatedInputs,
                      instanceTypeDB,
                      standAloneTaskSource)

    // create TaskExecutor
    val taskExectuor = TaskExecutor(jobMeta, streamAllFiles = false, Some(workerPaths))

    // run the steps of task execution in order
    taskExectuor.apply(TaskAction.Prolog) shouldBe "success Prolog"
    taskExectuor.apply(TaskAction.InstantiateCommand) shouldBe "success InstantiateCommand"

    // execute the shell script in a child job
    val script: Path = workerPaths.getCommandFile()
    //println(FileUtils.readFileContent(script))
    if (Files.exists(script)) {
      // this will throw an exception if the script exits with a non-zero return code
      logger.ignore(SysUtils.execCommand(script.toString))
    }
    //println(FileUtils.readFileContent(workerPaths.stdout))

    // epilog
    taskExectuor.apply(TaskAction.Epilog) shouldBe "success Epilog"

    if (outputsExpected.isDefined) {
      val outputs = jobMeta.outputs.getOrElse(Map.empty)
      outputs shouldBe outputsExpected.get
    }
  }

  it should "execute a simple WDL task" in {
    runTask("add")
  }

  it should "execute a WDL task with expressions" in {
    runTask("float_arith")
  }

  it should "evaluate expressions in runtime section" in {
    runTask("expressions_runtime_section")
  }

  it should "evaluate expressions in runtime section II" in {
    runTask("expressions_runtime_section_2")
  }

  it should "evaluate a command section" in {
    runTask("sub")
  }

  it should "run ps in a command section" in {
    runTask("ps")
  }

  it should "localize a file to a task" in {
    runTask("cgrep")
  }

  it should "handle type coercion" in {
    runTask("cast")
  }

  it should "handle spaces in file paths" in {
    runTask("spaces_in_file_paths")
  }

  it should "read_tsv" in {
    runTask("read_tsv_x")
  }

  it should "write_tsv" in {
    runTask("write_tsv_x")
  }

  it should "optimize task with an empty command section" in {
    val _ = runTask("empty_command_section")
    //task.commandSectionEmpty should be(true)
  }

  it should "handle structs" taggedAs EdgeTest in {
    runTask("Person2")
  }

  it should "handle missing optional files" in {
    runTask("missing_optional_output_file")
  }

  it should "run a python script" in {
    runTask("python_heredoc")
  }

  it should "deserialize good JSON" in {
    val goodJson = Map(
        "a" -> JsObject(
            Map(
                "type" -> JsString("Int"),
                "value" -> JsNumber(5)
            )
        ),
        "b" -> JsObject(
            Map(
                "type" -> JsObject(
                    "name" -> JsString("Array"),
                    "type" -> JsString("Float"),
                    "nonEmpty" -> JsBoolean(false)
                ),
                "value" -> JsArray(Vector(JsNumber(1.0), JsNumber(2.5)))
            )
        )
    )
    val v = WdlTaskSupport.deserializeValues(goodJson, Map.empty)
    v shouldBe Map(
        "a" -> (WdlTypes.T_Int, WdlValues.V_Int(5)),
        "b" -> (WdlTypes.T_Array(WdlTypes.T_Float), WdlValues.V_Array(
            Vector(WdlValues.V_Float(1.0), WdlValues.V_Float(2.5))
        ))
    )
  }

  it should "detect bad JSON" in {
    val badJson = Map("a" -> JsNumber(1), "b" -> JsString("hello"))
    assertThrows[Exception] {
      WdlTaskSupport.deserializeValues(badJson, Map.empty)
    }
  }
}