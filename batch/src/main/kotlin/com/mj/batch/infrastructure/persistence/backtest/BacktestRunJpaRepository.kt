package com.mj.batch.infrastructure.persistence.backtest

import org.springframework.data.jpa.repository.JpaRepository

interface BacktestRunJpaRepository : JpaRepository<BacktestRunJpa, String>
