# magicJar 백업 디렉토리

> **이 폴더는 .gitignore 대상** — git push로 보존되지 않습니다. 외부 매체(클라우드/USB) 복사가 의무입니다.
> 전체 가이드: [`docs/operations/backup-and-restore-guide.html`](../../docs/operations/backup-and-restore-guide.html) · 새 PC 복원: [`docs/operations/new-pc-migration-runbook.md`](../../docs/operations/new-pc-migration-runbook.md)
> 이 경로는 Mac manual/off-host fallback 산출물이다. NAS autonomous primary는 `/volume1/docker/magicjar/backups/sets/<KST timestamp>/` exact-six combined set이며, installer·DSM artifact·restore drill 전 readiness는 PENDING이다. 권위: [`docs/operations/backup-setup.md`](../../docs/operations/backup-setup.md).

## Mac fallback 구조

```
_workspace/backups/
├── postgres/
│   ├── daily/YYYY-MM-DD.dump   # 매일 03:00 KST · pg_dump custom format(-Fc) · 7일 회전
│   └── cron.log                # cron 실행 로그
└── redis/
    ├── snapshots/dump-YYYY-MM-DD-HHMM.rdb   # 매일 03:30 KST · 3일 회전
    └── cron.log
```

## fallback 생성 주체

| 파일 | 스크립트 | 주기 |
|------|---------|------|
| `postgres/daily/*.dump` | `scripts/backup/pg-dump-daily.sh` | cron 03:00 (수동: `bash scripts/backup/pg-dump-daily.sh`) |
| `redis/snapshots/*.rdb` | `scripts/backup/redis-snapshot.sh` | cron 03:30 |

## 복원

```bash
bash scripts/backup/pg-restore.sh --dry-run                                  # 미리보기
bash scripts/backup/pg-restore.sh --target-db magicjar_restore_test --drop-after  # 연습 (운영 무손상)
bash scripts/backup/pg-restore.sh --target-db magicjar --confirm-prod        # 운영 DR (앱 정지 선행)
```

## ⚠️ 핵심 경고 2건

1. **`.env` + dump = 한 세트** — KIS 자격증명은 DB에 AES 암호화 저장이며 마스터키(`MAGICJAR_SECRET_MASTER_KEY`)는 `.env`에만 있음. dump만으로는 복호화 불가.
2. **1년 초과 1m 캔들은 dump가 유일한 복원원** — KIS 서버 보관 1년 한도 (ADR 0105).
