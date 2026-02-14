package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser


/*
    how to convert to CPS format
    https://lisperator.net/pltut/compiler/cps-transformer
*/
class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>()
{

    private val sb = StringBuilder()
    private val userFns = HashSet<String>()
    private typealias Then = () -> Unit
    private var indent = 0
    private var tmpId = 0
    val returnValueName = "__continuation"

    fun mapType(t: MiniKotlinParser.TypeContext): String =
        when {
            t.INT_TYPE() != null -> "Integer"
            t.STRING_TYPE() != null -> "String"
            t.BOOLEAN_TYPE() != null -> "Boolean"
            t.UNIT_TYPE() != null -> "Void"  // usually use Void and pass null
            else -> error("unknown type: ${t.text}")
        }


    private fun emitLine(s: String = "")
    {
        val prefix = " ".repeat(indent)
        sb.append(prefix)
        sb.append(s).append('\n')
    }


    private fun emitBinary(left: MiniKotlinParser.ExpressionContext, right: MiniKotlinParser.ExpressionContext,
                           op: String, onValue: (String)->Unit)
    {
        emitExpr(left) { l ->
            emitExpr(right) { r ->
                onValue("(($l) $op ($r))")
            }
        }
    }

    private fun emitPrimary(p: MiniKotlinParser.PrimaryContext): String
    {
        if (p is MiniKotlinParser.IntLiteralContext)
        {
            return p.INTEGER_LITERAL().text
        }
        if (p is MiniKotlinParser.StringLiteralContext)
        {
            return p.STRING_LITERAL().text
        }
        if (p is MiniKotlinParser.BoolLiteralContext)
        {
            return p.BOOLEAN_LITERAL().text
        }
        if (p is MiniKotlinParser.IdentifierExprContext)
        {
            return p.IDENTIFIER().text
        }

        // Paren handled in emitExpr, not here
        throw RuntimeException("emitPrimary: unsupported primary: " + p.javaClass.simpleName + " text=" + p.text)
    }

    private fun emitCallExpr(call: MiniKotlinParser.FunctionCallExprContext, onValue: (String)->Unit)
    {

        val fname = call.IDENTIFIER().text
        val targetName =
            if (userFns.contains(fname)) fname
            else "Prelude.$fname"
        val args = call.argumentList()?.expression().orEmpty()

        fun evalArg(i: Int, acc: MutableList<String>)
        {
            if (i == args.size)
            {
                val res = fresh(returnValueName)
                emitLine("$targetName(${acc.joinToString(", ")}, ($res) -> {")
                indent+=2
                onValue(res)
                indent-=2
                emitLine("});")
                return
            }

            emitExpr(args[i]) { v ->
                acc.add(v)
                evalArg(i + 1, acc)
            }
        }

        evalArg(0, mutableListOf())
    }
    private fun emitExpr(e: MiniKotlinParser.ExpressionContext, onValue: (String) -> Unit)
    {
        when (e)
        {

            is MiniKotlinParser.PrimaryExprContext -> {
                val v = emitPrimary(e.primary())
                onValue(v)
                return
            }

            is MiniKotlinParser.NotExprContext -> {
                emitExpr(e.expression(), { x ->
                    onValue("(!($x))")
                })
                return
            }

            is MiniKotlinParser.AddSubExprContext -> {
                val op = e.getChild(1).text // "+" or "-"
                emitBinary(e.expression(0), e.expression(1), op, onValue)
                return
            }

            is MiniKotlinParser.MulDivExprContext -> {
                val op = e.getChild(1).text // "*", "/", "%"
                emitBinary(e.expression(0), e.expression(1), op, onValue)
                return
            }

            is MiniKotlinParser.ComparisonExprContext -> {
                val op = e.getChild(1).text // "<", "<=", ">", ">="
                emitBinary(e.expression(0), e.expression(1), op, onValue)
                return
            }

            is MiniKotlinParser.EqualityExprContext -> {
                val op = e.getChild(1).text // "==" or "!="
                emitBinary(e.expression(0), e.expression(1), op, onValue)
                return
            }

            is MiniKotlinParser.AndExprContext -> {
                emitBinary(e.expression(0), e.expression(1), "&&", onValue)
                return
            }

            is MiniKotlinParser.OrExprContext -> {
                emitBinary(e.expression(0), e.expression(1), "||", onValue)
                return
            }

            is MiniKotlinParser.FunctionCallExprContext -> {
                emitCallExpr(e, onValue)
                return
            }

            else -> error("emitExpr: unsupported expression: ${e.javaClass.simpleName} text=${e.text}")
        }
    }

    private fun emitReturn(stmt: MiniKotlinParser.ReturnStatementContext, kExpr: String)
    {
        val e = stmt.expression()
        if (e == null)
        {
            emitLine("$kExpr.accept(null);")
            emitLine("return;")
            return
        }
        emitExpr(e, { valueExpr ->
            emitLine("$kExpr.accept($valueExpr);")
            emitLine("return;")
        })
    }

    private fun fresh(prefix: String): String
    {
        val s = "__" + prefix + tmpId
        tmpId++
        return s
    }
    private fun emitWhile(wh: MiniKotlinParser.WhileStatementContext, then: Then, kExpr: String, retJava: String)
    {

    }


    private fun emitBlockIf(block: MiniKotlinParser.BlockContext, then: Then, kExpr: String, retJava: String)
    {
        val stmts = block.statement()
        emitStatementsList(stmts, 0, then, kExpr, retJava)
    }

    private fun emitIf(ifs: MiniKotlinParser.IfStatementContext, then: Then, kExpr: String, retJava: String)
    {
        val condExpr = ifs.expression()
        val thenBlock = ifs.block(0)
        val elseBlock = if (ifs.block().size > 1) ifs.block(1) else null

        emitExpr(condExpr) { c ->
            emitLine("if ($c) {")
            indent++
            emitBlockIf(thenBlock, then, kExpr, retJava)
            indent--
            emitLine("}")

            if (elseBlock != null)
            {
                emitLine("else {")
                indent++
                emitBlockIf(elseBlock, then, kExpr, retJava)
                indent--
                emitLine("}")
            }
            else
            {
                //we still need the false path, to not skip statement after if
                emitLine("else {")
                indent++
                then()
                indent--
                emitLine("}")
            }
        }
    }


    private fun emitBlock(block: MiniKotlinParser.BlockContext, kExpr: String, retJava: String)
    {
        val stmts = block.statement()
        emitStatementsList(stmts, 0, {}, kExpr, retJava)
    }

    private fun emitAssign(asg: MiniKotlinParser.VariableAssignmentContext, then: Then)
    {
        val name = asg.IDENTIFIER().text
        val rhs = asg.expression()

        emitExpr(rhs,) { v ->
                emitLine("$name = $v;")
                then()
            }

    }

    private fun emitVarDecl(vd: MiniKotlinParser.VariableDeclarationContext, then: Then)
    {
        val name = vd.IDENTIFIER().text
        val javaType = mapType(vd.type())
        val init = vd.expression()

        emitExpr(init) { v ->
            emitLine("$javaType $name = $v;")
            then()
        }
    }
    private fun emitStatementsList(
        stmts: List<MiniKotlinParser.StatementContext>,
        i: Int,
        then: Then,
        kExpr: String,
        retJava: String
    )
    {
        if (i >= stmts.size)
        {
            then()
            return
        }

        val s = stmts[i]

        val ret = s.returnStatement()
        if (ret != null)
        {
            emitReturn(ret, kExpr)
            return
        }

        val vd = s.variableDeclaration()
        if (vd != null)
        {
            emitVarDecl(vd,{emitStatementsList(stmts, i + 1, then, kExpr, retJava) } )
            return
        }

        val asg = s.variableAssignment()
        if (asg != null)
        {
            emitAssign(asg) { emitStatementsList(stmts, i + 1, then, kExpr, retJava) }
            return
        }

        val ifs = s.ifStatement()
        if (ifs != null)
        {
            emitIf(ifs, { emitStatementsList(stmts, i + 1, then, kExpr, retJava) }, kExpr, retJava)
            return
        }

        val wh = s.whileStatement()
        if (wh != null)
        {
            emitWhile(wh, { emitStatementsList(stmts, i + 1, then, kExpr, retJava) }, kExpr, retJava)
            return
        }

        val expr = s.expression()
        if (expr != null)
        {
            emitExpr(expr)
            {
                ignored ->
                    emitStatementsList(stmts, i + 1, then, kExpr, retJava)
            }

            return
        }

        throw RuntimeException("unknown statement: " + s.text)
    }


    private fun emitFunction(fn: MiniKotlinParser.FunctionDeclarationContext)
    {
        val name = fn.IDENTIFIER().text

        val javaRet = mapType(fn.type())
        val contType = "Continuation<$javaRet>"

        sb.append("public static void ")
        sb.append(name)
        sb.append("(")

        if (name == "main")
        {
            sb.append("String[] args")
        }
        else
        {
            val params = fn.parameterList()?.parameter().orEmpty()
            for (i in 0 until params.size)
            {
                val p = params[i]
                val pName = p.IDENTIFIER().text
                val pType = mapType(p.type())

                sb.append(pType).append(" ").append(pName)
                sb.append(", ")
            }
            sb.append(contType).append(returnValueName)
        }

        sb.append(")\n")
        sb.append("{\n")

        // we need to pass continueation to block since block might hit return
        indent+=2
        emitBlock(fn.block(),returnValueName, javaRet)
        indent-=2
        emitLine("}")
    }
    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String
    {
        sb.clear()
        userFns.clear()

        for (fn in program.functionDeclaration())
        {
            userFns.add(fn.IDENTIFIER().text)
        }
        emitLine("public class $className")
        emitLine("{")
        for (fn in program.functionDeclaration())
        {
            emitFunction(fn)
            emitLine()
        }
        emitLine("}")
        return sb.toString()

    }

}
