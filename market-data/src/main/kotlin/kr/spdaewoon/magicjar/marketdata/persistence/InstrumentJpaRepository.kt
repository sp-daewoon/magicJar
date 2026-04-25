package kr.spdaewoon.magicjar.marketdata.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface InstrumentJpaRepository : JpaRepository<InstrumentJpa, String>
