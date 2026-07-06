package kr.spdaewoon.magicjar.persistence.shared.trendline

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * `trendline` 테이블의 **anchor 조회 전용** 읽기 엔티티 (ADR 0140 §3-2 · R2 backend-core).
 *
 * ### 왜 별도 엔티티인가 (모듈 합류 판단 — 헌법 §11-1)
 * 정본 `trendline` write 엔티티(api 모듈 `TrendlineJpaEntity`)는 **api 모듈**에 있어
 * consumer(실시간 평가)가 볼 수 없다(consumer 는 api 에 의존하지 않음, master-architecture §5). 평가 어댑터는
 * api·consumer 양쪽 ApplicationContext 에 합류해야 하므로(ADR 0088 패턴), 공유 모듈 **persistence-shared** 에
 * 조회 전용 엔티티를 둔다. Hibernate 는 한 테이블에 복수 엔티티 매핑을 허용하며(api 정본 + 본 read row),
 * 서로 다른 엔티티명(class simpleName)이라 충돌하지 않는다. 매핑 컬럼만 로드(라벨·색상 등 미사용 컬럼 제외).
 *
 * **읽기 전용** — 평가 경로는 anchor 를 절대 수정하지 않는다(선 mutate 는 api `TrendlineService` 정본 경로 단독).
 *
 * kotlin-jpa 플러그인(persistence-shared 적용) 이 no-arg constructor 생성. 헌법 §6: body 가진 fun 0건.
 */
@Entity
@Table(name = "trendline")
class TrendlineAnchorRow(
    @Id
    @Column(name = "id")
    val id: Long = 0,
    @Column(name = "anchor1_ts", nullable = false)
    val anchor1Ts: Instant,
    @Column(name = "anchor1_price", precision = 19, scale = 4, nullable = false)
    val anchor1Price: BigDecimal,
    @Column(name = "anchor2_ts", nullable = false)
    val anchor2Ts: Instant,
    @Column(name = "anchor2_price", precision = 19, scale = 4, nullable = false)
    val anchor2Price: BigDecimal,
    @Column(name = "extend_right", nullable = false)
    val extendRight: Boolean = true,
    @Column(name = "extend_left", nullable = false)
    val extendLeft: Boolean = false,
)
