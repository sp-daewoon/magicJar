package com.mj.domain.market.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface InstrumentJpaRepository : JpaRepository<InstrumentJpa, String>
