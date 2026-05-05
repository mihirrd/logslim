import json

def extract_messages(input_file, output_file=None):
    messages = []

    with open(input_file, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue

            try:
                obj = json.loads(line)
                msg = obj.get("message")
                if msg:
                    messages.append(msg)
            except json.JSONDecodeError as e:
                # skip bad lines but keep track
                print(f"[WARN] Skipping invalid JSON at line {line_num}: {e}")

    # either print or write to file
    if output_file:
        with open(output_file, "w", encoding="utf-8") as f:
            for msg in messages:
                f.write(msg + "\n")
    else:
        for msg in messages:
            print(msg)

    return messages


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Extract messages from JSONL logs")
    parser.add_argument("input_file", help="Path to JSONL log file")
    parser.add_argument("--output", help="Optional output file")

    args = parser.parse_args()

    extract_messages(args.input_file, args.output)