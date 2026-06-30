#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_SET = ROOT / "artifacts" / "model_set.json"
DEFAULT_REPORT_JSON = ROOT / "artifacts" / "reports" / "model_validation_report.json"
DEFAULT_REPORT_HTML = ROOT / "artifacts" / "reports" / "model_validation_report.html"


@dataclass
class ArtifactResult:
    model_id: str
    artifact_id: str
    path: str
    exists: bool
    size_bytes: int | None
    format: str
    runtime: str
    backend: str
    platforms: list[str]
    required: bool
    notes: str
    warnings: list[str]


def load_model_set(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def inspect_artifact(model_id: str, artifact: dict[str, Any]) -> ArtifactResult:
    rel_path = artifact["path"]
    file_path = ROOT / rel_path
    warnings: list[str] = []
    exists = file_path.exists()
    size_bytes = file_path.stat().st_size if exists and file_path.is_file() else None

    if artifact["format"] == "pte" and not exists:
        warnings.append("ExecuTorch artifact missing; export step has not completed.")
    if artifact["format"] == "onnx" and not exists:
        warnings.append("Piper model missing; stage the voice asset bundle before runtime integration.")
    if artifact["format"] == "tflite" and exists and size_bytes is not None and size_bytes > 5 * 1024 * 1024:
        warnings.append("Detector artifact is larger than expected for mobile deployment.")
    if artifact["format"] == "torchscript" and exists and size_bytes is not None and size_bytes > 80 * 1024 * 1024:
        warnings.append("TorchScript fallback is large; prefer the portable .pte artifact for final deployment.")
    if artifact["required"] and not exists:
        warnings.append("Required artifact missing.")

    return ArtifactResult(
        model_id=model_id,
        artifact_id=artifact["id"],
        path=rel_path,
        exists=exists,
        size_bytes=size_bytes,
        format=artifact["format"],
        runtime=artifact["runtime"],
        backend=artifact["backend"],
        platforms=artifact["platforms"],
        required=artifact["required"],
        notes=artifact.get("notes", ""),
        warnings=warnings,
    )


def make_json_report(model_set: dict[str, Any], results: list[ArtifactResult]) -> dict[str, Any]:
    generated_at = datetime.now(timezone.utc).isoformat()
    return {
        "generatedAt": generated_at,
        "summary": {
            "totalArtifacts": len(results),
            "presentArtifacts": sum(1 for item in results if item.exists),
            "missingRequiredArtifacts": sum(1 for item in results if item.required and not item.exists),
            "modelsCovered": len(model_set["models"]),
        },
        "models": [
            {
                "id": model["id"],
                "displayName": model["displayName"],
                "purpose": model["purpose"],
                "status": model["status"],
                "artifacts": [
                    {
                        "artifactId": item.artifact_id,
                        "path": item.path,
                        "exists": item.exists,
                        "sizeBytes": item.size_bytes,
                        "format": item.format,
                        "runtime": item.runtime,
                        "backend": item.backend,
                        "platforms": item.platforms,
                        "required": item.required,
                        "notes": item.notes,
                        "warnings": item.warnings,
                    }
                    for item in results
                    if item.model_id == model["id"]
                ],
            }
            for model in model_set["models"]
        ],
    }


def render_html(report: dict[str, Any]) -> str:
    cards = []
    for model in report["models"]:
        rows = []
        for artifact in model["artifacts"]:
            state = "present" if artifact["exists"] else "missing"
            size_text = f"{artifact['sizeBytes'] / (1024 * 1024):.2f} MB" if artifact["sizeBytes"] else "n/a"
            warnings = "<br>".join(artifact["warnings"]) if artifact["warnings"] else "None"
            rows.append(
                f"""
                <tr>
                  <td>{artifact['artifactId']}</td>
                  <td>{artifact['format']}</td>
                  <td>{artifact['runtime']}</td>
                  <td>{artifact['backend']}</td>
                  <td>{', '.join(artifact['platforms'])}</td>
                  <td class="{state}">{state}</td>
                  <td>{size_text}</td>
                  <td>{artifact['path']}</td>
                  <td>{warnings}</td>
                </tr>
                """
            )
        cards.append(
            f"""
            <section class="card">
              <h2>{model['displayName']}</h2>
              <p><strong>Status:</strong> {model['status']}<br><strong>Purpose:</strong> {model['purpose']}</p>
              <table>
                <thead>
                  <tr>
                    <th>Artifact</th>
                    <th>Format</th>
                    <th>Runtime</th>
                    <th>Backend</th>
                    <th>Platforms</th>
                    <th>State</th>
                    <th>Size</th>
                    <th>Path</th>
                    <th>Warnings</th>
                  </tr>
                </thead>
                <tbody>
                  {''.join(rows)}
                </tbody>
              </table>
            </section>
            """
        )

    summary = report["summary"]
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SnapKnow Model Artifact Report</title>
  <style>
    :root {{
      --bg: #f5efe6;
      --ink: #1f2a37;
      --card: #fffdf9;
      --line: #d8cdbf;
      --accent: #0f766e;
      --warn: #b45309;
      --good: #166534;
      --bad: #991b1b;
    }}
    body {{
      margin: 0;
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: linear-gradient(180deg, #f5efe6, #efe3d3);
      color: var(--ink);
    }}
    main {{
      max-width: 1200px;
      margin: 0 auto;
      padding: 32px 20px 56px;
    }}
    h1 {{
      font-size: clamp(2rem, 4vw, 3.2rem);
      margin: 0 0 8px;
    }}
    .lede {{
      max-width: 760px;
      line-height: 1.5;
    }}
    .stats {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
      margin: 24px 0 28px;
    }}
    .stat, .card {{
      background: var(--card);
      border: 1px solid var(--line);
      border-radius: 18px;
      box-shadow: 0 8px 24px rgba(31, 42, 55, 0.08);
    }}
    .stat {{
      padding: 16px;
    }}
    .stat strong {{
      display: block;
      font-size: 1.8rem;
      color: var(--accent);
    }}
    .card {{
      padding: 18px;
      overflow-x: auto;
      margin-bottom: 18px;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
      font-size: 0.94rem;
    }}
    th, td {{
      text-align: left;
      padding: 10px 8px;
      border-top: 1px solid var(--line);
      vertical-align: top;
    }}
    th {{
      color: #4b5563;
      font-weight: 700;
    }}
    .present {{ color: var(--good); font-weight: 700; }}
    .missing {{ color: var(--bad); font-weight: 700; }}
    footer {{
      margin-top: 24px;
      color: #6b7280;
      font-size: 0.92rem;
    }}
  </style>
</head>
<body>
  <main>
    <h1>SnapKnow Model Artifact Report</h1>
    <p class="lede">This off-device report tracks which model artifacts are present, which ones are still placeholders, and which formats are shared across Android and macOS.</p>
    <div class="stats">
      <div class="stat"><strong>{summary['totalArtifacts']}</strong>Total artifacts</div>
      <div class="stat"><strong>{summary['presentArtifacts']}</strong>Present locally</div>
      <div class="stat"><strong>{summary['missingRequiredArtifacts']}</strong>Missing required</div>
      <div class="stat"><strong>{summary['modelsCovered']}</strong>Model groups</div>
    </div>
    {''.join(cards)}
    <footer>Generated at {report['generatedAt']}. This report is safe to host as a static page later if you want a Vercel viewer.</footer>
  </main>
</body>
</html>
"""


def strip_trailing_whitespace(text: str) -> str:
    return "\n".join(line.rstrip() for line in text.splitlines()) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate SnapKnow model artifacts.")
    parser.add_argument("--model-set", default=str(DEFAULT_MODEL_SET))
    parser.add_argument("--report-json", default=str(DEFAULT_REPORT_JSON))
    parser.add_argument("--report-html", default=str(DEFAULT_REPORT_HTML))
    args = parser.parse_args()

    model_set_path = Path(args.model_set)
    report_json_path = Path(args.report_json)
    report_html_path = Path(args.report_html)

    model_set = load_model_set(model_set_path)
    results = [
        inspect_artifact(model["id"], artifact)
        for model in model_set["models"]
        for artifact in model["artifacts"]
    ]
    report = make_json_report(model_set, results)

    report_json_path.parent.mkdir(parents=True, exist_ok=True)
    report_json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    report_html_path.write_text(strip_trailing_whitespace(render_html(report)), encoding="utf-8")

    print(f"Wrote JSON report: {report_json_path}")
    print(f"Wrote HTML report: {report_html_path}")

    missing_required = report["summary"]["missingRequiredArtifacts"]
    if missing_required:
        print(f"Validation completed with {missing_required} missing required artifact(s).")
        return 2

    print("Validation completed successfully.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
