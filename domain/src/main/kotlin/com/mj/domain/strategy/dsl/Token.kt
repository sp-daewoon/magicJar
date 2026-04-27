package com.mj.domain.strategy.dsl

internal enum class TokenType {
    NUMBER,
    IDENTIFIER,
    LPAREN,
    RPAREN,
    COMMA,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    LT,
    LTE,
    GT,
    GTE,
    EQ,
    NEQ,
    AND,
    OR,
    NOT,
    EOF,
}

internal data class Token(
    val type: TokenType,
    val text: String,
    val pos: Int,
)
