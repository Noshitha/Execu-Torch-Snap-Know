#!/usr/bin/env python3
import argparse
import csv
import json
import math
import os
import statistics
import time
from pathlib import Path

import numpy as np
import psutil
import tensorflow as tf


def format_mb(num_bytes: int) -> float:
    return round(num_bytes / (1024 * 1024), 2)


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return float("nan")
    return float(np.percentile(values, pct))


def tensor_bytes(shape, dtype) -> int:
    size = int(np.prod(shape))
    return size * np.dtype(dtype).itemsize


def interpreter_tensor_bytes(interpreter: tf.lite.Interpreter) -> int:
    total = 0
    seen = set()
    for detail in interpreter.get_tensor_details():
        shape = tuple(int(x) for x in detail["shape"])
        key = (shape, str(np.dtype(detail["dtype"])))
        if key in seen:
            continue
        seen.add(key)
        total += tensor_bytes(shape, detail["dtype"])
    return total


def make_sample_input(input_detail: dict) -> np.ndarray:
    shape = tuple(int(x) for x in input_detail["shape"])
    dtype = input_detail["dtype"]
    if np.issubdtype(dtype, np.integer):
        info = np.iinfo(dtype)
        low = max(info.min, 0)
        high = min(info.max, 255)
        return np.random.randint(low, high + 1, size=shape, dtype=dtype)
    return np.random.random(shape).astype(dtype)


def benchmark_model(
    model_path: Path,
    runs: int,
    warmup_runs: int,
    resize_height: int,
    resize_width: int,
) -> dict:
    process = psutil.Process(os.getpid())
    rss_before_load = process.memory_info().rss

    load_start = time.perf_counter()
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    input_detail = interpreter.get_input_details()[0]
    input_shape = list(input_detail["shape"])
    shape_signature = list(input_detail["shape_signature"])

    if shape_signature[1] == -1 or shape_signature[2] == -1 or input_shape[1] == 1 or input_shape[2] == 1:
        resized_shape = [input_shape[0], resize_height, resize_width, input_shape[3]]
        interpreter.resize_tensor_input(input_detail["index"], resized_shape, strict=False)

    interpreter.allocate_tensors()
    load_ms = (time.perf_counter() - load_start) * 1000
    rss_after_allocate = process.memory_info().rss

    input_detail = interpreter.get_input_details()[0]
    actual_input_shape = tuple(int(x) for x in input_detail["shape"])
    sample_input = make_sample_input(input_detail)
    input_bytes = tensor_bytes(actual_input_shape, input_detail["dtype"])
    tensor_arena_bytes = interpreter_tensor_bytes(interpreter)

    for _ in range(warmup_runs):
        interpreter.set_tensor(input_detail["index"], sample_input)
        interpreter.invoke()

    interpreter.set_tensor(input_detail["index"], sample_input)
    cold_start = time.perf_counter()
    interpreter.invoke()
    cold_ms = (time.perf_counter() - cold_start) * 1000

    run_times = []
    for _ in range(runs):
        interpreter.set_tensor(input_detail["index"], sample_input)
        start = time.perf_counter()
        interpreter.invoke()
        run_times.append((time.perf_counter() - start) * 1000)

    rss_after_runs = process.memory_info().rss
    output_details = interpreter.get_output_details()
    output_bytes = sum(
        tensor_bytes(tuple(int(x) for x in detail["shape"]), detail["dtype"])
        for detail in output_details
    )

    result = {
        "model": model_path.name,
        "model_path": str(model_path),
        "file_size_mb": format_mb(model_path.stat().st_size),
        "load_and_allocate_ms": round(load_ms, 2),
        "cold_invoke_ms": round(cold_ms, 2),
        "avg_invoke_ms": round(statistics.mean(run_times), 2),
        "p50_invoke_ms": round(percentile(run_times, 50), 2),
        "p95_invoke_ms": round(percentile(run_times, 95), 2),
        "min_invoke_ms": round(min(run_times), 2),
        "max_invoke_ms": round(max(run_times), 2),
        "stdev_invoke_ms": round(statistics.pstdev(run_times), 2),
        "input_shape": list(actual_input_shape),
        "input_dtype": np.dtype(input_detail["dtype"]).name,
        "input_tensor_mb": format_mb(input_bytes),
        "output_tensor_mb": format_mb(output_bytes),
        "estimated_tensor_arena_mb": format_mb(tensor_arena_bytes),
        "rss_before_load_mb": format_mb(rss_before_load),
        "rss_after_allocate_mb": format_mb(rss_after_allocate),
        "rss_after_runs_mb": format_mb(rss_after_runs),
        "rss_delta_allocate_mb": format_mb(rss_after_allocate - rss_before_load),
        "rss_delta_total_mb": format_mb(rss_after_runs - rss_before_load),
        "warmup_runs": warmup_runs,
        "measured_runs": runs,
    }
    return result


def write_csv(results: list[dict], output_path: Path) -> None:
    if not results:
        return
    with output_path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)


def write_markdown(results: list[dict], output_path: Path) -> None:
    lines = [
        "# TFLite Benchmark Results",
        "",
        "| Model | Size (MB) | Load+Alloc (ms) | Cold (ms) | Avg (ms) | P50 (ms) | P95 (ms) | RSS Delta Alloc (MB) | Tensor Arena (MB) |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in results:
        lines.append(
            "| {model} | {file_size_mb} | {load_and_allocate_ms} | {cold_invoke_ms} | {avg_invoke_ms} | {p50_invoke_ms} | {p95_invoke_ms} | {rss_delta_allocate_mb} | {estimated_tensor_arena_mb} |".format(
                **row
            )
        )
    lines.append("")
    output_path.write_text("\n".join(lines))


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark local TFLite models.")
    parser.add_argument("--models-dir", default="benchmark_models")
    parser.add_argument("--output-dir", default="benchmark_results/latest")
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--warmup-runs", type=int, default=5)
    parser.add_argument("--height", type=int, default=320)
    parser.add_argument("--width", type=int, default=320)
    args = parser.parse_args()

    models_dir = Path(args.models_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    model_paths = sorted(models_dir.glob("*.tflite"))
    if not model_paths:
        raise SystemExit(f"No .tflite files found in {models_dir}")

    results = []
    for model_path in model_paths:
        results.append(
            benchmark_model(
                model_path=model_path,
                runs=args.runs,
                warmup_runs=args.warmup_runs,
                resize_height=args.height,
                resize_width=args.width,
            )
        )

    json_path = output_dir / "results.json"
    csv_path = output_dir / "results.csv"
    md_path = output_dir / "results.md"

    json_path.write_text(json.dumps(results, indent=2))
    write_csv(results, csv_path)
    write_markdown(results, md_path)

    print(json.dumps(results, indent=2))
    print(f"\nSaved JSON to {json_path}")
    print(f"Saved CSV to {csv_path}")
    print(f"Saved Markdown to {md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
