package kr.spdaewoon.magicjar.strategy.dsl

internal class Lexer(
    private val src: String,
) {
    private var i = 0

    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> {
                    out += Token(TokenType.LPAREN, "(", i)
                    i++
                }
                c == ')' -> {
                    out += Token(TokenType.RPAREN, ")", i)
                    i++
                }
                c == ',' -> {
                    out += Token(TokenType.COMMA, ",", i)
                    i++
                }
                c == '+' -> {
                    out += Token(TokenType.PLUS, "+", i)
                    i++
                }
                c == '-' -> {
                    out += Token(TokenType.MINUS, "-", i)
                    i++
                }
                c == '*' -> {
                    out += Token(TokenType.STAR, "*", i)
                    i++
                }
                c == '/' -> {
                    out += Token(TokenType.SLASH, "/", i)
                    i++
                }
                c == '<' -> out += readLeOrLt()
                c == '>' -> out += readGeOrGt()
                c == '=' -> out += readEq()
                c == '!' -> out += readNeq()
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    out += readNumber()
                }
                c.isLetter() || c == '_' -> out += readIdentifier()
                else -> throw DslParseException("unexpected character '$c' at $i")
            }
        }
        out += Token(TokenType.EOF, "", src.length)
        return out
    }

    private fun readLeOrLt(): Token {
        val start = i
        return if (i + 1 < src.length && src[i + 1] == '=') {
            i += 2
            Token(TokenType.LTE, "<=", start)
        } else {
            i++
            Token(TokenType.LT, "<", start)
        }
    }

    private fun readGeOrGt(): Token {
        val start = i
        return if (i + 1 < src.length && src[i + 1] == '=') {
            i += 2
            Token(TokenType.GTE, ">=", start)
        } else {
            i++
            Token(TokenType.GT, ">", start)
        }
    }

    private fun readEq(): Token {
        val start = i
        if (i + 1 < src.length && src[i + 1] == '=') {
            i += 2
            return Token(TokenType.EQ, "==", start)
        }
        throw DslParseException("expected '==' at $i")
    }

    private fun readNeq(): Token {
        val start = i
        if (i + 1 < src.length && src[i + 1] == '=') {
            i += 2
            return Token(TokenType.NEQ, "!=", start)
        }
        throw DslParseException("expected '!=' at $i")
    }

    private fun readNumber(): Token {
        val start = i
        while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
        return Token(TokenType.NUMBER, src.substring(start, i), start)
    }

    private fun readIdentifier(): Token {
        val start = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        val text = src.substring(start, i)
        val type =
            when (text.uppercase()) {
                "AND" -> TokenType.AND
                "OR" -> TokenType.OR
                "NOT" -> TokenType.NOT
                else -> TokenType.IDENTIFIER
            }
        return Token(type, text, start)
    }
}
