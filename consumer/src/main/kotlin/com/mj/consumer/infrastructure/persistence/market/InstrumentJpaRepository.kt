package com.mj.consumer.infrastructure.persistence.market

import org.springframework.data.jpa.repository.JpaRepository

interface InstrumentJpaRepository : JpaRepository<InstrumentJpa, String>
