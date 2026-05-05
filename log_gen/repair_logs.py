#!/usr/bin/env python3
"""
repair_logs.py — Stream, repair, and flatten Elasticsearch log exports.

Handles real-world corruption:
  - Invalid / non-UTF-8 bytes           → stripped or replaced
  - Truncated JSON objects              → skipped with warning
  - Trailing commas before ] or }       → removed
  - Unescaped control chars in strings  → escaped
  - Null bytes                          → removed
  - NaN / Infinity literals             → replaced with null
  - Mismatched / extra braces           → best-effort bracket balancing
  - Duplicate hits (same _id)           → deduplicated

Input format (Elasticsearch hits JSON):
  { "hits": { "hits": [ { "_id": "...", "fields": { ... } }, ... ] } }

Each hit is flattened from the `fields` sub-object (single-element arrays
are unwrapped) and written as one JSON object per line (JSONL) to the
output file.

Usage:
  python3 repair_logs.py input.log output.jsonl
  python3 repair_logs.py input.log output.jsonl --verbose
  python3 repair_logs.py input.log output.jsonl --no-dedup
"""

import re
import sys
import json
import argparse
import logging
from pathlib import Path
from typing import Iterator

# ──────────────────────────────────────────────────────────────────────────────
# Logging setup
# ──────────────────────────────────────────────────────────────────────────────

logging.basicConfig(
    format="%(levelname)-8s %(message)s",
    stream=sys.stderr,
)
log = logging.getLogger(__name__)


# ──────────────────────────────────────────────────────────────────────────────
# Step 1 — Raw bytes → clean unicode string
# ──────────────────────────────────────────────────────────────────────────────

def read_clean_text(path: Path) -> str:
    """
    Read a file, stripping / replacing any bytes that are not valid UTF-8.
    Also removes null bytes, which are never legal in JSON.
    """
    raw = path.read_bytes()
    # Replace null bytes
    raw = raw.replace(b"\x00", b"")
    # Decode: replace any invalid UTF-8 sequences with the replacement char
    text = raw.decode("utf-8", errors="replace")
    # Remove the replacement character itself so it doesn't confuse the parser
    text = text.replace("\ufffd", "")
    return text


# ──────────────────────────────────────────────────────────────────────────────
# Step 2 — Structural text repairs (applied before json.loads)
# ──────────────────────────────────────────────────────────────────────────────

# Matches NaN / Infinity / -Infinity as bare JSON values (not inside strings)
_NAN_INF = re.compile(r'\b(-?Infinity|NaN)\b')

# Trailing commas before a closing bracket/brace  e.g.  [1, 2,]  or  {"a":1,}
_TRAILING_COMMA = re.compile(r',\s*([}\]])')

# Unescaped literal tab / newline / carriage-return inside a JSON string.
# We only fix runs of chars that appear between (unescaped) double-quotes.
# Strategy: tokenise the text into string / non-string regions, then fix
# control chars only inside strings.
_CTRL_IN_STRING = re.compile(r'[\x00-\x1f]')


def _fix_control_chars_in_strings(text: str) -> str:
    """
    Walk the text character by character and escape raw control characters
    that appear inside JSON string literals.  Correctly handles \" escapes
    so we don't mis-identify the boundary of a string.
    """
    result = []
    in_string = False
    i = 0
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == '\\':
                # Escaped character — pass both chars through unchanged
                result.append(ch)
                i += 1
                if i < len(text):
                    result.append(text[i])
            elif ch == '"':
                in_string = False
                result.append(ch)
            elif ord(ch) < 0x20:
                # Raw control character inside string — escape it
                escapes = {'\n': '\\n', '\r': '\\r', '\t': '\\t',
                           '\b': '\\b', '\f': '\\f'}
                result.append(escapes.get(ch, f'\\u{ord(ch):04x}'))
            else:
                result.append(ch)
        else:
            if ch == '"':
                in_string = True
                result.append(ch)
            else:
                result.append(ch)
        i += 1
    return ''.join(result)


def repair_text(text: str) -> str:
    """Apply all text-level repairs in a safe order."""
    # 1. NaN / Infinity → null
    text = _NAN_INF.sub('null', text)
    # 2. Trailing commas
    text = _TRAILING_COMMA.sub(r'\1', text)
    # 3. Control characters inside strings
    text = _fix_control_chars_in_strings(text)
    return text


# ──────────────────────────────────────────────────────────────────────────────
# Step 3 — Locate and stream individual hit objects from the text
# ──────────────────────────────────────────────────────────────────────────────

def _find_hits_array_start(text: str) -> int:
    """
    Return the index of the '[' that opens the hits array.
    Works on the ES response shape:  { "hits": { "hits": [ ... ] } }
    Falls back to the first top-level '[' if that pattern isn't found.
    """
    # Look for  "hits": [   (possibly with whitespace)
    m = re.search(r'"hits"\s*:\s*\[', text)
    if m:
        # We want the position of '[' in the *second* hits key
        # (the outer "hits" object also has a "hits" array inside it)
        second = re.search(r'"hits"\s*:\s*\[', text[m.end():])
        if second:
            bracket_pos = m.end() + second.end() - 1
        else:
            bracket_pos = m.end() - 1
        return bracket_pos
    first_bracket = text.find('[')
    return first_bracket


def stream_hit_texts(text: str) -> Iterator[str]:
    """
    Yield raw text of each top-level JSON object inside the hits array.
    Uses a brace-depth counter so it handles nested objects correctly,
    including objects whose fields contain embedded JSON strings.
    """
    start = _find_hits_array_start(text)
    if start == -1:
        log.error("Could not locate a hits array in the input. Giving up.")
        return

    i = start + 1  # skip the opening '['
    n = len(text)

    while i < n:
        # Skip whitespace and commas between objects
        while i < n and text[i] in ' \t\n\r,':
            i += 1

        if i >= n or text[i] == ']':
            break  # end of array

        if text[i] != '{':
            log.warning("Expected '{' at position %d, got %r — skipping char", i, text[i])
            i += 1
            continue

        # Walk forward tracking brace depth, respecting strings
        depth = 0
        obj_start = i
        in_str = False
        j = i
        while j < n:
            ch = text[j]
            if in_str:
                if ch == '\\':
                    j += 2
                    continue
                elif ch == '"':
                    in_str = False
            else:
                if ch == '"':
                    in_str = True
                elif ch == '{':
                    depth += 1
                elif ch == '}':
                    depth -= 1
                    if depth == 0:
                        yield text[obj_start:j + 1]
                        i = j + 1
                        break
            j += 1
        else:
            # Never closed — truncated object
            log.warning("Truncated object starting at position %d — skipping", obj_start)
            break


# ──────────────────────────────────────────────────────────────────────────────
# Step 4 — Parse a single hit object with fallback bracket balancing
# ──────────────────────────────────────────────────────────────────────────────

def _balance_braces(text: str) -> str:
    """Add missing closing braces if the object is slightly truncated."""
    opens = text.count('{') - text.count('}')
    if opens > 0:
        text = text + '}' * opens
    return text


def parse_hit(raw: str) -> dict | None:
    """
    Parse a single hit object.  Tries:
      1. Plain json.loads
      2. After applying text repairs
      3. After bracket-balancing + repairs
    Returns None if all attempts fail.
    """
    for attempt, candidate in enumerate([
        raw,
        repair_text(raw),
        repair_text(_balance_braces(raw)),
    ]):
        try:
            return json.loads(candidate)
        except json.JSONDecodeError as exc:
            if attempt == 2:
                log.warning("Could not parse object (giving up): %s … error: %s",
                            raw[:80], exc)
    return None


# ──────────────────────────────────────────────────────────────────────────────
# Step 5 — Flatten an ES hit into a clean flat record
# ──────────────────────────────────────────────────────────────────────────────

def _unwrap(value):
    """Unwrap single-element lists (ES wraps every field value in a list)."""
    if isinstance(value, list) and len(value) == 1:
        return value[0]
    return value


def flatten_hit(hit: dict) -> dict:
    """
    Convert an ES hit object into a flat record:
      _id, _index  →  kept as metadata
      fields.*     →  promoted to top level, single-element arrays unwrapped
    """
    record: dict = {}

    # Metadata
    for meta in ("_id", "_index", "_score"):
        if meta in hit:
            record[meta] = hit[meta]

    # Fields
    fields = hit.get("fields", {})
    for key, value in fields.items():
        record[key] = _unwrap(value)

    return record


# ──────────────────────────────────────────────────────────────────────────────
# Main pipeline
# ──────────────────────────────────────────────────────────────────────────────

def process(input_path: Path, output_path: Path, dedup: bool = True, verbose: bool = False):
    log.setLevel(logging.DEBUG if verbose else logging.INFO)

    log.info("Reading %s …", input_path)
    raw_text = read_clean_text(input_path)
    log.info("Read %d chars. Applying top-level text repairs …", len(raw_text))
    repaired_text = repair_text(raw_text)

    seen_ids: set[str] = set()
    total = parsed = skipped_parse = skipped_dedup = 0

    with output_path.open("w", encoding="utf-8") as out:
        for raw_obj in stream_hit_texts(repaired_text):
            total += 1
            hit = parse_hit(raw_obj)

            if hit is None:
                skipped_parse += 1
                continue

            record = flatten_hit(hit)

            # Deduplication by _id
            hit_id = record.get("_id")
            if dedup and hit_id:
                if hit_id in seen_ids:
                    skipped_dedup += 1
                    log.debug("Duplicate _id=%s — skipping", hit_id)
                    continue
                seen_ids.add(hit_id)

            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            parsed += 1

            if verbose:
                ts = record.get("@timestamp", "")
                svc = record.get("service", "")
                lvl = record.get("level", "")
                log.debug("[%s] %-20s %-8s %s", ts, svc, lvl,
                          str(record.get("message", ""))[:60])

    log.info("─" * 60)
    log.info("Total objects found : %d", total)
    log.info("Successfully written: %d", parsed)
    log.info("Skipped (parse err) : %d", skipped_parse)
    log.info("Skipped (duplicate) : %d", skipped_dedup)
    log.info("Output              : %s", output_path)


# ──────────────────────────────────────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Stream, repair, and flatten Elasticsearch log exports to JSONL."
    )
    parser.add_argument("input",  type=Path, help="Input log file (.log / .json)")
    parser.add_argument("output", type=Path, help="Output JSONL file")
    parser.add_argument("--verbose",   action="store_true",
                        help="Print each parsed record to stderr")
    parser.add_argument("--no-dedup",  action="store_true",
                        help="Disable deduplication by _id")
    args = parser.parse_args()

    if not args.input.exists():
        sys.exit(f"Error: input file not found: {args.input}")

    process(
        input_path=args.input,
        output_path=args.output,
        dedup=not args.no_dedup,
        verbose=args.verbose,
    )


if __name__ == "__main__":
    main()