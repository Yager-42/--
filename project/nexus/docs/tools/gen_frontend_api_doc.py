#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
frontend-api.md helper.

Goals:
1) Check: docs/frontend-api.md "接口总表" covers all @RestController endpoints in nexus-trigger.
2) Update: generate a DTO dictionary section so FE can implement without reading backend code.

Design notes:
- No third-party deps. Regex + simple line scanning only.
- We only treat types ending with "DTO" as DTOs to document.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import re
from pathlib import Path
from typing import Iterable, Optional


@dataclasses.dataclass(frozen=True)
class Endpoint:
    http_method: str
    path: str
    controller: str
    method_name: str
    needs_login: bool
    role: str
    request_body_type: str
    response_data_type: str


@dataclasses.dataclass(frozen=True)
class DtoField:
    name: str
    type: str


@dataclasses.dataclass(frozen=True)
class DtoDef:
    name: str
    rel_path: str
    fields: list[DtoField]


MAPPING_ANNO_TO_HTTP = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
    # RequestMapping is skipped at method level in this repo (no method= present),
    # but keep it here for completeness.
    "RequestMapping": "",
}


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _join_paths(base: str, sub: str) -> str:
    if not base:
        base = ""
    if not sub:
        sub = ""
    if not base.startswith("/"):
        base = "/" + base
    if base != "/" and base.endswith("/"):
        base = base[:-1]
    if sub and not sub.startswith("/"):
        sub = "/" + sub
    full = (base + sub) if base != "/" else (sub or "/")
    # Normalize multiple slashes.
    full = re.sub(r"/{2,}", "/", full)
    return full


def _parse_sa_role(line: str) -> Optional[str]:
    m = re.search(r'@SaCheckRole\(\s*"([^"]+)"\s*\)', line)
    return m.group(1) if m else None


def _extract_string_literals(s: str) -> list[str]:
    # Keep it simple: repo uses plain string literals without escapes in mapping annotations.
    return re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', s)


def _parse_request_mapping_path(lines_before_class: list[str]) -> str:
    for line in lines_before_class:
        if "@RequestMapping" not in line:
            continue
        literals = _extract_string_literals(line)
        for lit in literals:
            if "/" in lit:
                return lit
    return ""


def _parse_class_name(lines: list[str]) -> str:
    for line in lines:
        m = re.search(r"\bclass\s+([A-Za-z0-9_]+)\b", line)
        if m:
            return m.group(1)
    return "UnknownController"


def _parse_whitelist_exclude_paths(webmvc_config_path: Path) -> set[str]:
    """
    Parse WebMvcConfig.excludePathPatterns("...") entries.
    """
    if not webmvc_config_path.exists():
        return set()
    text = _read_text(webmvc_config_path)
    # Find the excludePathPatterns(...) call region.
    m = re.search(r"\.excludePathPatterns\s*\(\s*(?P<body>[\s\S]*?)\)\s*;", text)
    if not m:
        return set()
    body = m.group("body")
    paths = set()
    for lit in _extract_string_literals(body):
        if lit.startswith("/"):
            paths.add(lit)
    return paths


def _matches_exclude(path: str, exclude_patterns: set[str]) -> bool:
    for p in exclude_patterns:
        if p.endswith("/**"):
            prefix = p[: -len("/**")]
            if path == prefix or path.startswith(prefix + "/"):
                return True
        elif path == p:
            return True
    return False


def _looks_like_method_mapping(lines: list[str], start_idx: int, max_lookahead: int = 20) -> bool:
    # Heuristic: a method mapping annotation is followed by "public ...(" within a few lines.
    for j in range(start_idx, min(len(lines), start_idx + max_lookahead)):
        if "public" in lines[j] and "(" in lines[j]:
            return True
    return False


def _collect_signature(lines: list[str], start_idx: int) -> tuple[str, int]:
    """
    Collect method signature lines until we see '{' (same line or later).
    Returns (signature_str, end_idx_inclusive).
    """
    buf: list[str] = []
    i = start_idx
    while i < len(lines):
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        buf.append(line)
        if "{" in line:
            break
        i += 1
    return " ".join(buf), i


def _parse_signature(signature: str) -> tuple[str, str, str]:
    """
    Returns (return_type, method_name, params_str).
    """
    # Remove throws clause to simplify parsing.
    signature = re.sub(r"\bthrows\b.+?\{", "{", signature)
    before_paren, _, rest = signature.partition("(")
    params_part, _, _ = rest.partition(")")

    tokens = before_paren.strip().split()
    # Find 'public' and take everything after it.
    try:
        pub_idx = tokens.index("public")
    except ValueError:
        pub_idx = 0

    # Handle "public Response<X> name" => return is tokens[-2], name is tokens[-1]
    method_name = tokens[-1]
    return_type = " ".join(tokens[pub_idx + 1 : -1]).strip()
    return return_type, method_name, params_part.strip()


def _split_params(params_str: str) -> list[str]:
    if not params_str.strip():
        return []
    parts: list[str] = []
    buf: list[str] = []
    depth_angle = 0
    depth_paren = 0
    in_string = False
    prev = ""
    for ch in params_str:
        if ch == '"' and prev != "\\":
            in_string = not in_string
        if not in_string:
            if ch == "<":
                depth_angle += 1
            elif ch == ">":
                depth_angle = max(0, depth_angle - 1)
            elif ch == "(":
                depth_paren += 1
            elif ch == ")":
                depth_paren = max(0, depth_paren - 1)
            elif ch == "," and depth_angle == 0 and depth_paren == 0:
                parts.append("".join(buf).strip())
                buf = []
                prev = ch
                continue
        buf.append(ch)
        prev = ch
    tail = "".join(buf).strip()
    if tail:
        parts.append(tail)
    return parts


def _parse_param_type_and_name(param: str) -> tuple[str, str, str]:
    """
    Returns (binding, type, name), binding in {"body","path","query","form","ignore"}.
    """
    binding = "query"
    if "@RequestBody" in param:
        binding = "body"
    elif "@PathVariable" in param:
        binding = "path"
    elif "@RequestParam" in param:
        binding = "form"

    # Drop annotations, keep simple tokens.
    cleaned = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", param).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    tokens = cleaned.split(" ")
    if len(tokens) < 2:
        return "ignore", "", ""
    p_type = " ".join(tokens[:-1]).strip()
    p_name = tokens[-1].strip()
    return binding, p_type, p_name


def _strip_generics(t: str) -> str:
    return re.sub(r"<.*>", "", t).strip()


def _extract_dto_tokens(type_str: str) -> set[str]:
    return {tok for tok in re.findall(r"\b[A-Z][A-Za-z0-9_]*DTO\b", type_str) if tok.endswith("DTO")}


def scan_endpoints(repo_root: Path) -> list[Endpoint]:
    trigger_src = repo_root / "nexus-trigger" / "src" / "main" / "java"
    webmvc_config = trigger_src / "cn" / "nexus" / "trigger" / "http" / "config" / "WebMvcConfig.java"
    exclude_paths = _parse_whitelist_exclude_paths(webmvc_config)

    endpoints: list[Endpoint] = []
    for p in trigger_src.rglob("*.java"):
        try:
            text = _read_text(p)
        except UnicodeDecodeError:
            continue
        if "@RestController" not in text:
            continue

        lines = text.splitlines()
        controller = _parse_class_name(lines)

        # Class-level annotations are typically before the "class" line.
        before_class: list[str] = []
        for line in lines:
            before_class.append(line)
            if re.search(r"\bclass\b", line):
                break
        base_path = _parse_request_mapping_path(before_class)
        class_role = ""
        for line in before_class:
            r = _parse_sa_role(line)
            if r:
                class_role = r
                break

        i = 0
        while i < len(lines):
            raw = lines[i].strip()
            if not raw.startswith("@"):
                i += 1
                continue

            anno_name = None
            for a in MAPPING_ANNO_TO_HTTP.keys():
                if raw.startswith("@" + a):
                    anno_name = a
                    break
            if not anno_name:
                i += 1
                continue

            # Collect annotation block in case it spans multiple lines.
            anno_block = [raw]
            j = i
            while "(" in "".join(anno_block) and ")" not in "".join(anno_block):
                j += 1
                if j >= len(lines):
                    break
                anno_block.append(lines[j].strip())
            anno_text = " ".join(anno_block)

            # Skip class-level mappings (not followed by a method).
            if not _looks_like_method_mapping(lines, j + 1):
                i = j + 1
                continue

            http_method = MAPPING_ANNO_TO_HTTP.get(anno_name, "")
            if anno_name == "RequestMapping":
                # This repo doesn't use method-level @RequestMapping; keep it conservative.
                i = j + 1
                continue

            paths = [""]
            lits = _extract_string_literals(anno_text)
            path_lits = [lit for lit in lits if "/" in lit]
            if path_lits:
                paths = path_lits

            # Collect method-level role annotations between mapping and signature.
            method_role = class_role
            k = j + 1
            while k < len(lines):
                line_k = lines[k].strip()
                if "public" in line_k and "(" in line_k:
                    break
                r = _parse_sa_role(line_k)
                if r:
                    method_role = r
                k += 1

            signature, sig_end = _collect_signature(lines, k)
            return_type, method_name, params_str = _parse_signature(signature)
            params = _split_params(params_str)

            request_body_type = ""
            for param in params:
                binding, p_type, _ = _parse_param_type_and_name(param)
                if binding == "body":
                    request_body_type = _strip_generics(p_type)
                    break

            response_data_type = ""
            rt = return_type.strip()
            if rt.startswith("Response<") and rt.endswith(">"):
                inner = rt[len("Response<") : -1].strip()
                response_data_type = inner if inner != "Void" else ""
            elif rt == "Response":
                response_data_type = ""
            else:
                # Non Response wrapper (e.g., String)
                response_data_type = rt

            for sub in paths:
                full_path = _join_paths(base_path, sub)
                needs_login = full_path.startswith("/api/v1/") and not _matches_exclude(full_path, exclude_paths)
                endpoints.append(
                    Endpoint(
                        http_method=http_method,
                        path=full_path,
                        controller=controller,
                        method_name=method_name,
                        needs_login=needs_login,
                        role=method_role,
                        request_body_type=request_body_type,
                        response_data_type=response_data_type,
                    )
                )

            i = sig_end + 1

    # Stable order for diff/readability.
    endpoints.sort(key=lambda e: (e.path, e.http_method, e.controller, e.method_name))
    return endpoints


def parse_doc_endpoint_table(doc_text: str) -> set[tuple[str, str]]:
    lines = doc_text.splitlines()
    start = None
    for idx, line in enumerate(lines):
        if line.strip().startswith("| 方法 | 路径 |"):
            start = idx + 2  # skip header + separator
            break
    if start is None:
        return set()
    endpoints: set[tuple[str, str]] = set()
    for line in lines[start:]:
        if not line.strip().startswith("|"):
            break
        cols = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cols) < 2:
            continue
        method = cols[0].strip("` ").upper()
        path = cols[1].strip("` ")
        if method and path.startswith("/"):
            endpoints.add((method, path))
    return endpoints


def collect_dto_defs(repo_root: Path) -> dict[str, DtoDef]:
    roots = [
        repo_root / "nexus-api" / "src" / "main" / "java",
        repo_root / "nexus-trigger" / "src" / "main" / "java",
    ]
    field_re = re.compile(
        r"^\s*private\s+(?!static)(?P<type>[^;]+?)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*;",
        re.MULTILINE,
    )
    out: dict[str, DtoDef] = {}
    for root in roots:
        if not root.exists():
            continue
        for p in root.rglob("*.java"):
            name = p.stem
            if not name.endswith("DTO"):
                continue
            try:
                text = _read_text(p)
            except UnicodeDecodeError:
                continue
            if "class " not in text:
                continue
            fields: list[DtoField] = []
            for m in field_re.finditer(text):
                t = m.group("type").strip()
                t = re.sub(r"^\s*final\s+", "", t)
                fields.append(DtoField(name=m.group("name"), type=t))
            rel = p.relative_to(repo_root).as_posix()
            out[name] = DtoDef(name=name, rel_path=rel, fields=fields)
    return out


def _example_value_for_type(
    type_str: str,
    dto_defs: dict[str, DtoDef],
    depth: int,
    max_depth: int,
) -> object:
    t = type_str.strip()
    # Normalize common wrappers.
    if t in {"String", "CharSequence"}:
        return "string"
    if t in {"Long", "long", "Integer", "int", "Short", "short"}:
        return 0
    if t in {"Boolean", "boolean"}:
        return False
    if t in {"Double", "double", "Float", "float"}:
        return 0.0

    # Common timestamp-ish fields: keep it a number to match many DTOs.
    if t in {"Date", "Instant", "LocalDateTime", "LocalDate", "OffsetDateTime"}:
        return "2026-03-20T12:34:56"

    # List / Set
    m = re.match(r"^(List|Set)<\s*(.+)\s*>$", t)
    if m:
        inner = m.group(2).strip()
        return [_example_value_for_type(inner, dto_defs, depth + 1, max_depth)]

    # Map
    m = re.match(r"^Map<\s*([^,]+)\s*,\s*(.+)\s*>$", t)
    if m:
        v = m.group(2).strip()
        return {"key": _example_value_for_type(v, dto_defs, depth + 1, max_depth)}

    # DTO recursion
    if t.endswith("DTO") and t in dto_defs:
        if depth >= max_depth:
            return {}
        return _example_object_for_dto(dto_defs[t], dto_defs, depth + 1, max_depth)

    # Fallback for unknown types.
    if t.endswith("DTO"):
        return {}
    return None


def _example_object_for_dto(dto: DtoDef, dto_defs: dict[str, DtoDef], depth: int, max_depth: int) -> dict:
    obj: dict[str, object] = {}
    for f in dto.fields:
        obj[f.name] = _example_value_for_type(f.type, dto_defs, depth, max_depth)
    return obj


def generate_dto_dict_markdown(seed_dtos: set[str], dto_defs: dict[str, DtoDef]) -> str:
    # Expand transitive closure via field references.
    all_dtos: set[str] = set()
    queue = [d for d in sorted(seed_dtos) if d in dto_defs]
    while queue:
        d = queue.pop(0)
        if d in all_dtos:
            continue
        all_dtos.add(d)
        for f in dto_defs[d].fields:
            for child in _extract_dto_tokens(f.type):
                if child in dto_defs and child not in all_dtos:
                    queue.append(child)

    ordered = sorted(all_dtos)
    lines: list[str] = []
    lines.append("> 说明：下面只保证“字段名 + 类型”准确；示例 JSON 只是为了让你看懂形状，不代表真实业务值。")
    lines.append("")
    lines.append("### DTO 索引")
    for name in ordered:
        lines.append(f"- `{name}`")
    lines.append("")

    for name in ordered:
        dto = dto_defs[name]
        lines.append(f"### `{dto.name}`")
        lines.append("")
        lines.append(f"- 来源：`{dto.rel_path}`")
        if dto.fields:
            lines.append("- 字段：")
            for f in dto.fields:
                lines.append(f"  - `{f.name}`: `{f.type}`")
        else:
            lines.append("- 字段：无（空 DTO）")
        lines.append("")
        example_obj = _example_object_for_dto(dto, dto_defs, depth=0, max_depth=2)
        lines.append("示例 JSON：")
        lines.append("```json")
        lines.append(json.dumps(example_obj, ensure_ascii=False, indent=2))
        lines.append("```")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def replace_between_markers(doc_text: str, marker_start: str, marker_end: str, new_body: str) -> str:
    """
    Upsert DTO dict section and always place it at the end of the document.

    Reason: users might accidentally insert the marker block in the middle of "接口详情",
    which would break the reading flow. This function removes any existing marker block
    (and its section header when present) and appends a fresh section at the end.
    """
    text = doc_text
    if marker_start in text and marker_end in text:
        start_idx = text.index(marker_start)
        end_idx = text.index(marker_end) + len(marker_end)

        # Try to also remove the section header line right before the marker.
        header_idx = text.rfind("\n## 3) DTO 字典", 0, start_idx)
        if header_idx == -1 and text.startswith("## 3) DTO 字典"):
            header_idx = 0
        if header_idx != -1:
            start_idx = header_idx

        text = (text[:start_idx].rstrip() + "\n\n" + text[end_idx:].lstrip()).rstrip() + "\n"

    # Append the section at the end.
    return (
        text.rstrip()
        + "\n\n## 3) DTO 字典（前端不看代码也能对接）\n\n"
        + marker_start
        + "\n"
        + new_body.rstrip()
        + "\n"
        + marker_end
        + "\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Nexus frontend-api.md helper")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Only check endpoint table coverage; do not write files.",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="Update DTO dictionary section and check endpoint table coverage (default when no flags).",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    doc_path = repo_root / "docs" / "frontend-api.md"
    if not doc_path.exists():
        raise SystemExit(f"docs not found: {doc_path}")

    endpoints = scan_endpoints(repo_root)
    doc_text = _read_text(doc_path)
    doc_eps = parse_doc_endpoint_table(doc_text)
    code_eps = {(e.http_method, e.path) for e in endpoints}

    missing = sorted(code_eps - doc_eps)
    extra = sorted(doc_eps - code_eps)

    def print_diff() -> None:
        if missing:
            print("Missing in docs table:")
            for m in missing:
                print(f"- {m[0]} {m[1]}")
        if extra:
            print("Extra in docs table (not found in code):")
            for x in extra:
                print(f"- {x[0]} {x[1]}")

    if args.check and not args.write:
        if missing or extra:
            print_diff()
            return 1
        print("OK: endpoint table covers all controller endpoints.")
        return 0

    # Default behavior: write if no flags.
    do_write = args.write or (not args.check and not args.write)

    if do_write:
        dto_defs = collect_dto_defs(repo_root)
        seed_dtos: set[str] = set()
        for e in endpoints:
            seed_dtos |= _extract_dto_tokens(e.request_body_type)
            seed_dtos |= _extract_dto_tokens(e.response_data_type)
        dto_md = generate_dto_dict_markdown(seed_dtos, dto_defs)
        new_text = replace_between_markers(
            doc_text,
            "<!-- DTO-DICT:START -->",
            "<!-- DTO-DICT:END -->",
            dto_md,
        )
        doc_path.write_text(new_text, encoding="utf-8")

    if missing or extra:
        print_diff()
        return 1
    print("OK: docs updated; endpoint table covers all controller endpoints.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
