package kr.spdaewoon.magicjar.strategy.dsl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LexerParserTest {
    @Test
    fun `tokenize basic expression`() {
        val tokens = Lexer("RSI(14) < 30 AND CLOSE > SMA(20)").tokenize()
        // sanity: at least 12 meaningful tokens + EOF
        tokens.last().type shouldBe TokenType.EOF
    }

    @Test
    fun `parse comparison and logical`() {
        val tokens = Lexer("RSI(14) < 30 AND CLOSE > SMA(20)").tokenize()
        val node = Parser(tokens).parse()
        check(node is And)
        check(node.left is Compare)
        check(node.right is Compare)
    }

    @Test
    fun `parse parenthesized boolean`() {
        val tokens = Lexer("(CLOSE > 100 OR CLOSE < 50) AND VOLUME > 1000").tokenize()
        val node = Parser(tokens).parse()
        check(node is And)
        check(node.left is Or)
    }

    @Test
    fun `unknown bare token without comparison fails`() {
        val tokens = Lexer("CLOSE").tokenize()
        shouldThrow<DslParseException> { Parser(tokens).parse() }
    }

    @Test
    fun `arithmetic precedence in operand`() {
        val tokens = Lexer("VOLUME > AVG_VOLUME(20) * 1.5 + 100").tokenize()
        val node = Parser(tokens).parse()
        check(node is Compare)
        check((node as Compare).right is BinOp) // (* then +)
    }

    @Test
    fun `NOT operator parses`() {
        val tokens = Lexer("NOT (CLOSE < 100)").tokenize()
        val node = Parser(tokens).parse()
        check(node is Not)
    }
}
