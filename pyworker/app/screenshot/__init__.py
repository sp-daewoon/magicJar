"""헤드리스 차트·페이지 캡처 + S3/MinIO 업로드.

spec §11-4: 1차 LWC takeScreenshot, 실패 시 pyworker /screenshot fallback.
P3 골격은 fallback 경로 (Playwright + MinIO) 만 구현.
"""
