package com.mj.api.infrastructure.persistence.market

import org.springframework.data.jpa.repository.JpaRepository

interface InstrumentJpaRepository : JpaRepository<InstrumentJpa, String>
