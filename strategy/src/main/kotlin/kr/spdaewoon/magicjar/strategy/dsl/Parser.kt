package kr.spdaewoon.magicjar.strategy.dsl

import java.math.BigDecimal

/**
 * 재귀 하강 파서. 외부 라이브러리 의존 없음.
 *
 * 문법:
 * ```
 * expr        = orExpr
 * orExpr      = andExpr ("OR" andExpr)*
 * andExpr     = notExpr ("AND" notExpr)*
 * notExpr     = "NOT" notExpr | comparison
 * comparison  = arith (compareOp arith)?
 * arith       = mulExpr (("+" | "-") mulExpr)*
 * mulExpr     = unary (("*" | "/") unary)*
 * unary       = "-" unary | primary
 * primary     = NUMBER | IDENT ("(" args? ")")? | "(" expr ")"
 * ```
 *
 * 비교 연산자가 없는 식은 BoolNode 위치에서 사용 시 오류.
 */
internal class Parser(
    tokens: List<Token>,
) {
    private val tokens = tokens
    private var pos = 0

    fun parse(): BoolNode {
        val node = parseOr()
        expect(TokenType.EOF)
        return node
    }

    private fun parseOr(): BoolNode {
        var left = parseAnd()
        while (peek().type == TokenType.OR) {
            advance()
            val right = parseAnd()
            left = Or(left, right)
        }
        return left
    }

    private fun parseAnd(): BoolNode {
        var left = parseNot()
        while (peek().type == TokenType.AND) {
            advance()
            val right = parseNot()
            left = And(left, right)
        }
        return left
    }

    private fun parseNot(): BoolNode {
        if (peek().type == TokenType.NOT) {
            advance()
            return Not(parseNot())
        }
        return parseComparison()
    }

    private fun parseComparison(): BoolNode {
        // Special-case: parenthesized boolean expression
        if (peek().type == TokenType.LPAREN && containsBoolOpInsideParen()) {
            advance() // (
            val node = parseOr()
            expect(TokenType.RPAREN)
            return node
        }
        val left = parseArith()
        val opTok = peek()
        if (opTok.type !in COMPARE_OPS) {
            throw DslParseException(
                "expected comparison operator at pos ${opTok.pos}, found '${opTok.text}'",
            )
        }
        advance()
        val right = parseArith()
        return Compare(left, opTok.type, right)
    }

    private fun containsBoolOpInsideParen(): Boolean {
        var depth = 0
        var i = pos
        while (i < tokens.size) {
            val t = tokens[i]
            when (t.type) {
                TokenType.LPAREN -> depth++
                TokenType.RPAREN -> {
                    depth--
                    if (depth == 0) return false
                }
                TokenType.AND, TokenType.OR, TokenType.NOT -> {
                    if (depth == 1) return true
                }
                TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
                TokenType.EQ, TokenType.NEQ,
                -> {
                    if (depth == 1) return true
                }
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    private fun parseArith(): NumNode {
        var left = parseMul()
        while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
            val op = peek().type
            advance()
            val right = parseMul()
            left = BinOp(left, op, right)
        }
        return left
    }

    private fun parseMul(): NumNode {
        var left = parseUnary()
        while (peek().type == TokenType.STAR || peek().type == TokenType.SLASH) {
            val op = peek().type
            advance()
            val right = parseUnary()
            left = BinOp(left, op, right)
        }
        return left
    }

    private fun parseUnary(): NumNode {
        if (peek().type == TokenType.MINUS) {
            advance()
            return Neg(parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): NumNode {
        val t = peek()
        return when (t.type) {
            TokenType.NUMBER -> {
                advance()
                NumLiteral(BigDecimal(t.text))
            }
            TokenType.IDENTIFIER -> {
                advance()
                if (peek().type == TokenType.LPAREN) {
                    advance() // (
                    val args = mutableListOf<NumNode>()
                    if (peek().type != TokenType.RPAREN) {
                        args += parseArith()
                        while (peek().type == TokenType.COMMA) {
                            advance()
                            args += parseArith()
                        }
                    }
                    expect(TokenType.RPAREN)
                    Call(t.text.uppercase(), args)
                } else {
                    Call(t.text.uppercase(), emptyList())
                }
            }
            TokenType.LPAREN -> {
                advance()
                val node = parseArith()
                expect(TokenType.RPAREN)
                node
            }
            else -> throw DslParseException(
                "unexpected token '${t.text}' at pos ${t.pos}",
            )
        }
    }

    private fun peek(): Token = tokens[pos]

    private fun advance(): Token = tokens[pos++]

    private fun expect(type: TokenType) {
        val t = peek()
        if (t.type != type) {
            throw DslParseException(
                "expected $type but got ${t.type} ('${t.text}') at pos ${t.pos}",
            )
        }
        pos++
    }

    companion object {
        private val COMPARE_OPS =
            setOf(
                TokenType.LT,
                TokenType.LTE,
                TokenType.GT,
                TokenType.GTE,
                TokenType.EQ,
                TokenType.NEQ,
            )
    }
}
