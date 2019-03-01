package dxWDL.util

import java.nio.file.{Path, Paths}
import org.scalatest.{FlatSpec, Matchers}
import wom.callable.{WorkflowDefinition}
import wom.graph._
import wom.types._

class BlockTest extends FlatSpec with Matchers {
    private def pathFromBasename(dir: String, basename: String) : Path = {
        val p = getClass.getResource(s"/${dir}/${basename}").getPath
        Paths.get(p)
    }

    it should "calculate closure correctly" in {
        val path = pathFromBasename("util", "block_closure.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (_, subBlocks, _) = Block.split(wf.innerGraph, wfSourceCode)

        Block.closure(subBlocks(1)).keys.toSet should be(Set("flag", "rain"))
        Block.closure(subBlocks(2)).keys.toSet should be(Set("flag", "inc1.result"))
        Block.closure(subBlocks(3)).keys.toSet should be(Set("rain"))
        Block.closure(subBlocks(4)).keys.toSet should be(Set("rain", "inc1.result", "flag"))
    }

    it should "calculate outputs correctly" in {
        val path = pathFromBasename("util", "block_closure.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (_, subBlocks, _) = Block.split(wf.innerGraph, wfSourceCode)

        Block.outputs(subBlocks(1)) should be(Map("inc2.result" -> WomOptionalType(WomIntegerType)))
        Block.outputs(subBlocks(2)) should be(Map("inc3.result" -> WomOptionalType(WomIntegerType)))
        Block.outputs(subBlocks(3)) should be(Map("inc4.result" -> WomArrayType(WomIntegerType)))
        Block.outputs(subBlocks(4)) should be(
            Map("x" -> WomArrayType(WomIntegerType),
                "inc5.result" -> WomArrayType(WomOptionalType(WomIntegerType)))
        )
    }

    it should "calculate outputs correctly II" in {
        val path = pathFromBasename("compiler", "wf_linear.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (_, subBlocks, _) = Block.split(wf.innerGraph, wfSourceCode)

        Block.outputs(subBlocks(1)) should be(Map("z" -> WomIntegerType,
                                                  "mul.result" -> WomIntegerType))
        Block.closure(subBlocks(1)).keys.toSet should be(Set("add.result"))
    }

    it should "handle block zero" taggedAs(EdgeTest) in {
        val path = pathFromBasename("util", "block_zero.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (inNodes, subBlocks, outNodes) = Block.split(wf.innerGraph, wfSourceCode)

        Block.outputs(subBlocks(0)) should be(
            Map("rain" -> WomIntegerType,
                "inc.result" -> WomOptionalType(WomIntegerType))
        )
    }

    it should "block with two calls or more" in {
        val path = pathFromBasename("util", "block_with_three_calls.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (_, subBlocks, _) = Block.split(wf.innerGraph, wfSourceCode)
        Utils.ignore(subBlocks)
    }

    it should "calculate closure correctly for WDL draft-2" in {
        val path = pathFromBasename("draft2", "block_closure.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (_, subBlocks, _) = Block.split(wf.innerGraph, wfSourceCode)

        Block.closure(subBlocks(1)).keys.toSet should be(Set("flag", "rain"))
        Block.closure(subBlocks(2)).keys.toSet should be(Set("flag", "inc1.result"))
        Block.closure(subBlocks(3)).keys.toSet should be(Set("rain"))
        Block.closure(subBlocks(4)).keys.toSet should be(Set("rain", "inc1.result", "flag"))
    }

    it should "calculate closure correctly for WDL draft-2 II" in {
        val path = pathFromBasename("draft2", "shapes.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val (inputNodes, subBlocks, outputNodes) = Block.split(wf.innerGraph, wfSourceCode)

        Block.closure(subBlocks(0)).keys.toSet should be(Set("num"))
        Block.closure(subBlocks(1)).keys.toSet should be(Set.empty)
    }

    it should "calculate closure for a workflow with expression outputs" in {
        val path = pathFromBasename("compiler", "wf_with_output_expressions.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)

        val outputNodes = wf.innerGraph.outputNodes
        val exprOutputNodes: Vector[ExpressionBasedGraphOutputNode] =
            outputNodes.flatMap{ node =>
                if (Block.isSimpleOutput(node)) None
                else Some(node.asInstanceOf[ExpressionBasedGraphOutputNode])
            }.toVector
        Block.outputClosure(exprOutputNodes) should be (Set("a", "b"))
    }

    it should "calculate output closure for a workflow" in {
        val path = pathFromBasename("compiler", "cast.wdl")
        val wfSourceCode = Utils.readFileContent(path)
        val wf : WorkflowDefinition = ParseWomSourceFile.parseWdlWorkflow(wfSourceCode)
        val outputNodes = wf.innerGraph.outputNodes.toVector
        Block.outputClosure(outputNodes) should be (Set("Add.result",
                                                        "SumArray.result",
                                                        "SumArray2.result",
                                                        "JoinMisc.result"))
    }
}
