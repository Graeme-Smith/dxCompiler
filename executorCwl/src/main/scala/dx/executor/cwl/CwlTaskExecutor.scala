package dx.executor.cwl

import dx.cwl._
import dx.core.io.StreamFiles.StreamFiles
import dx.core.ir.{Type, Value}
import dx.core.languages.cwl.{CwlUtils, RequirementEvaluator}
import dx.executor.{FileUploader, JobMeta, TaskExecutor}
import dx.util.TraceLevel

object CwlTaskExecutor {
  def create(jobMeta: JobMeta,
             fileUploader: FileUploader,
             streamFiles: StreamFiles): CwlTaskExecutor = {
    if (!Parser.canParse(jobMeta.sourceCode)) {
      throw new Exception(
          s"""source code does not appear to be a CWL document of a supported version
             |${jobMeta.sourceCode}""".stripMargin
      )
    }
    val tool = Parser.parseString(jobMeta.sourceCode) match {
      case tool: CommandLineTool => tool
      case other =>
        throw new Exception(s"expected CWL document to contain a CommandLineTool, not ${other}")
    }
    CwlTaskExecutor(tool, jobMeta, fileUploader, streamFiles)
  }
}

case class CwlTaskExecutor(tool: CommandLineTool,
                           jobMeta: JobMeta,
                           fileUploader: FileUploader,
                           streamFiles: StreamFiles)
    extends TaskExecutor(jobMeta, fileUploader, streamFiles) {

  private val logger = jobMeta.logger

  override def executorName: String = "dxExecutorCwl"

  private lazy val inputParams: Map[String, CommandInputParameter] = {
    tool.inputs.collect {
      case param if param.id.forall(_.name.isDefined) =>
        param.id.get.name.get -> param
    }.toMap
  }

  private lazy val runtime: Runtime = CwlUtils.createRuntime(jobMeta.workerPaths)

  private def cwlInputs: Map[String, (CwlType, CwlValue)] = {
    val missingTypes = jobMeta.primaryInputs.keySet.diff(inputParams.keySet)
    if (missingTypes.nonEmpty) {
      throw new Exception(s"no type information given for input(s) ${missingTypes.mkString(",")}")
    }
    // convert IR to CWL values; discard auxiliary fields
    val evaluator = Evaluator.create(tool.requirements)
    inputParams.foldLeft(Map.empty[String, (CwlType, CwlValue)]) {
      case (env, (name, param)) =>
        val cwlTypes = param.types
        val (cwlType, cwlValue) = jobMeta.primaryInputs.get(name) match {
          case Some(irValue) =>
            CwlUtils.fromIRValue(irValue, cwlTypes, name)
          case None if param.default.isDefined =>
            val ctx = CwlUtils.createEvauatorContext(runtime, ctx)
            evaluator.evaluate(param.default.get, cwlTypes, ctx)
          case None if cwlTypes.exists(CwlOptional.isOptional) =>
            (CwlNull, NullValue)
          case _ =>
            throw new Exception(s"Missing required input ${name} to tool ${tool.id}")
        }
        env + (name -> (cwlType, cwlValue))
    }
  }

  private def printInputs(inputs: Map[String, (CwlType, CwlValue)]): Unit = {
    if (logger.isVerbose) {
      val inputStr = tool.inputs
        .flatMap {
          case param if param.id.forall(_.name.forall(inputs.contains)) =>
            val name = param.id.get.name.get
            Some(s"${name} -> (${param.types}, ${inputs.get(name)})")
          case other =>
            logger.trace(s"no input for parameter ${other}")
            None
        }
        .mkString("\n")
      logger.traceLimited(s"inputs: ${inputStr}")
    }
  }

  private lazy val defaultRuntimeAttrs: Map[String, (CwlType, CwlValue)] = {
    CwlUtils.fromIR(jobMeta.defaultRuntimeAttrs)
  }

  private def getRequiredInstanceType(
      inputs: Map[String, (CwlType, CwlValue)] = cwlInputs
  ): String = {
    logger.traceLimited("calcInstanceType", minLevel = TraceLevel.VVerbose)
    printInputs(inputs)
    val cwlEvaluator = Evaluator.create(tool.requirements)
    val ctx = CwlUtils.createEvauatorContext(runtime)
    val env = cwlEvaluator.evaluateMap(inputs, ctx)
    val reqEvaluator = RequirementEvaluator(
        tool.requirements,
        defaultRuntimeAttrs ++ env,
        jobMeta.workerPaths
    )
    val request = reqEvaluator.parseInstanceType
    logger.traceLimited(s"calcInstanceType $request")
    jobMeta.instanceTypeDb.apply(request).name
  }

  override protected lazy val getRequiredInstanceType: String = getRequiredInstanceType()

  override protected def getInputsWithDefaults: Map[String, (Type, Value)] = {
    val inputs = cwlInputs
    printInputs(inputs)
    CwlUtils.toIR(inputs)
  }

  override protected def streamFileForInput(parameterName: String): Boolean = {
    inputParams(parameterName).streamable.getOrElse(false)
  }

  override protected def getSchemas: Map[String, Type.TSchema] = {
    RequirementUtils.getSchemaDefs(tool.requirements).collect {
      case (name, record: CwlRecord) => name -> CwlUtils.toIRSchema(record)
    }
  }

  override protected def writeCommandScript(
      localizedInputs: Map[String, (Type, Value)]
  ): Map[String, (Type, Value)] = ???

  private lazy val outputParams: Map[String, CommandOutputParameter] = {
    tool.outputs.map {
      case param if param.id.forall(_.name.isDefined) =>
        param.id.get.name.get -> param
    }.toMap
  }

  override protected def evaluateOutputs(
      localizedInputs: Map[String, (Type, Value)]
  ): Map[String, (Type, Value)] = {
    val irInputs = CwlUtils.fromIR(localizedInputs)
    val cwlEvaluator = Evaluator.create(tool.requirements)
    val ctx = CwlUtils.createEvauatorContext(runtime)
    val localizedOutputs = cwlEvaluator.evaluateMap(irInputs, ctx)
    CwlUtils.toIR(localizedOutputs)
  }

  override protected def outputTypes: Map[String, Type] = {
    outputParams.map {
      case (name, param) if param.types.size == 1 =>
        name -> CwlUtils.toIRType(param.types.head)
      case (_, param) =>
        throw new Exception(s"Multi-type outputs are not supported ${param}")
    }
  }
}
