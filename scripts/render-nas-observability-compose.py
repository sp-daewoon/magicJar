#!/usr/bin/env python3
"""Render a remote-Docker-safe observability Compose overlay.

Local config files are embedded as Compose ``configs.content`` so a remote
Docker daemon never resolves repository-relative bind mounts on the NAS host.
The rendered file contains no credentials; secrets stay in the env file.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OBS = ROOT / "docker" / "observability"

CONFIGS = {
    "prometheus_config": (OBS / "prometheus" / "prometheus.nas.yml", "/etc/prometheus/prometheus.yml"),
    "prometheus_rules": (
        OBS / "prometheus" / "rules" / "magicjar-alerts.yml",
        "/etc/prometheus/rules/magicjar-alerts.yml",
    ),
    "grafana_datasource": (
        OBS / "grafana" / "provisioning" / "datasources" / "prometheus.yml",
        "/etc/grafana/provisioning/datasources/prometheus.yml",
    ),
    "grafana_dashboard_provider": (
        OBS / "grafana" / "provisioning" / "dashboards" / "magicjar.yml",
        "/etc/grafana/provisioning/dashboards/magicjar.yml",
    ),
    "grafana_dashboard_jvm": (
        OBS / "grafana" / "dashboards" / "magicjar-jvm.json",
        "/var/lib/grafana/dashboards/magicjar-jvm.json",
    ),
    "grafana_dashboard_slo": (
        OBS / "grafana" / "dashboards" / "magicjar-slo.json",
        "/var/lib/grafana/dashboards/magicjar-slo.json",
    ),
    "grafana_dashboard_kis": (
        OBS / "grafana" / "dashboards" / "magicjar-kis-ratelimit.json",
        "/var/lib/grafana/dashboards/magicjar-kis-ratelimit.json",
    ),
    "alertmanager_config": (
        OBS / "alertmanager" / "alertmanager.yml",
        "/etc/alertmanager/alertmanager.yml",
    ),
    "alertmanager_noslack": (
        OBS / "alertmanager" / "alertmanager-noslack.yml",
        "/etc/alertmanager/alertmanager-noslack.yml",
    ),
}

PROMETHEUS_IMAGE = "prom/prometheus:v2.54.1@sha256:69961df6ffa67598048a31aa2822d61f3c93b91d7db24e44d9bb03f99d520da9"
GRAFANA_IMAGE = "grafana/grafana:11.2.0@sha256:37a5d8860aef847dfa09f5f8947f010f6479f98cf7820b5186f9c6314b44be60"
ALERTMANAGER_IMAGE = "prom/alertmanager:v0.27.0@sha256:45c3a586d4332d710bef92b06e90380c0b1f588968c00100d4b5e4e44c40ca5f"


def config_mounts(names: list[str]) -> str:
    lines: list[str] = []
    for name in names:
        target = CONFIGS[name][1]
        lines.extend((f"      - source: {name}", f"        target: {target}"))
    return "\n".join(lines)


def content_block(path: Path) -> str:
    # Compose interpolation also runs inside configs.content. Escape every '$'
    # so Prometheus templates ($labels/$value) and Grafana variables survive.
    text = path.read_text(encoding="utf-8").rstrip("\n").replace("$", "$$")
    return "\n".join(f"      {line}" if line else "      " for line in text.splitlines())


def render() -> str:
    prometheus_mounts = config_mounts(["prometheus_config", "prometheus_rules"])
    grafana_mounts = config_mounts(
        [
            "grafana_datasource",
            "grafana_dashboard_provider",
            "grafana_dashboard_jvm",
            "grafana_dashboard_slo",
            "grafana_dashboard_kis",
        ]
    )
    alertmanager_mounts = config_mounts(["alertmanager_config", "alertmanager_noslack"])
    services = f'''name: magicjar-observability
services:
  prometheus:
    image: {PROMETHEUS_IMAGE}
    pull_policy: never
    container_name: magicjar-prometheus
    restart: unless-stopped
    stop_grace_period: 30s
    labels:
      org.opencontainers.image.revision: "${{MAGICJAR_OBSERVABILITY_REVISION:?revision required}}"
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=7d
      - --storage.tsdb.retention.size=2GB
      - --web.enable-lifecycle
    configs:
{prometheus_mounts}
    volumes:
      - prometheus_data:/prometheus
    ports:
      - "${{NAS_BIND_ADDRESS:-127.0.0.1}}:${{PROMETHEUS_HOST_PORT:-9090}}:9090"
    networks: [magicjar_core]
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:9090/-/healthy 2>&1 | grep -q Healthy"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 15s

  grafana:
    image: {GRAFANA_IMAGE}
    pull_policy: never
    container_name: magicjar-grafana
    restart: unless-stopped
    stop_grace_period: 30s
    labels:
      org.opencontainers.image.revision: "${{MAGICJAR_OBSERVABILITY_REVISION:?revision required}}"
    depends_on:
      prometheus:
        condition: service_healthy
    environment:
      TZ: Asia/Seoul
      GF_SECURITY_ADMIN_USER: "${{GRAFANA_ADMIN_USER:-admin}}"
      GF_SECURITY_ADMIN_PASSWORD: "${{GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD required}}"
      GF_AUTH_ANONYMOUS_ENABLED: "false"
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_INSTALL_PLUGINS: ""
    configs:
{grafana_mounts}
    volumes:
      - grafana_data:/var/lib/grafana
    ports:
      - "${{NAS_BIND_ADDRESS:-127.0.0.1}}:${{GRAFANA_HOST_PORT:-3001}}:3000"
    networks: [magicjar_core]
    healthcheck:
      test:
        - CMD-SHELL
        - >-
          wget -qO- http://localhost:3000/api/health 2>&1 | grep -q '"database": "ok"'
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 20s

  alertmanager:
    image: {ALERTMANAGER_IMAGE}
    pull_policy: never
    container_name: magicjar-alertmanager
    restart: unless-stopped
    stop_grace_period: 30s
    labels:
      org.opencontainers.image.revision: "${{MAGICJAR_OBSERVABILITY_REVISION:?revision required}}"
    environment:
      TZ: Asia/Seoul
      ALERTMANAGER_SLACK_WEBHOOK: "${{ALERTMANAGER_SLACK_WEBHOOK:-}}"
    entrypoint: ["/bin/sh", "-c"]
    command:
      - |
        CFG=/etc/alertmanager/alertmanager-noslack.yml
        if [ -n "$$ALERTMANAGER_SLACK_WEBHOOK" ]; then
          sed "s|__SLACK_WEBHOOK_URL__|$$ALERTMANAGER_SLACK_WEBHOOK|g" /etc/alertmanager/alertmanager.yml > /run/magicjar-alertmanager/alertmanager.yml
          CFG=/run/magicjar-alertmanager/alertmanager.yml
        fi
        exec /bin/alertmanager --config.file="$$CFG" --storage.path=/alertmanager
    configs:
{alertmanager_mounts}
    volumes:
      - alertmanager_data:/alertmanager
    tmpfs:
      - /run/magicjar-alertmanager:size=1m,mode=0700
    ports:
      - "${{NAS_BIND_ADDRESS:-127.0.0.1}}:${{ALERTMANAGER_HOST_PORT:-9093}}:9093"
    networks: [magicjar_core]
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:9093/-/healthy >/dev/null 2>&1"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 10s

networks:
  magicjar_core:
    external: true
    name: magicjar_default

volumes:
  prometheus_data:
    name: magicjar_observability_prometheus_data
  grafana_data:
    name: magicjar_observability_grafana_data
  alertmanager_data:
    name: magicjar_observability_alertmanager_data

configs:
'''
    rendered = [services]
    for name, (path, _) in CONFIGS.items():
        if not path.is_file():
            raise FileNotFoundError(path)
        rendered.append(f"  {name}:\n    content: |\n{content_block(path)}\n")
    return "".join(rendered)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--images", action="store_true")
    args = parser.parse_args()
    if args.images:
        print(PROMETHEUS_IMAGE)
        print(GRAFANA_IMAGE)
        print(ALERTMANAGER_IMAGE)
        return
    text = render()
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
