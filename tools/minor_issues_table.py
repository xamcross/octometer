#!/usr/bin/env python3
"""Print the merged MINOR findings table.

The script reads each file under minor_issues/. It prints one table,
with the column "Pull request" first, sorted by the pull request number.
It also prints the row count and the count of open rows.
"""

import pathlib
import re
import sys

COLUMNS = ["Date", "Issue", "Reviewer role", "File and line", "Finding", "Suggested fix", "Status"]

ROW_PATTERN = re.compile(r"^\|\s*(\d{4}-\d{2}-\d{2})\s*\|")


def pull_request_number(path: pathlib.Path) -> int:
    """Return the pull request number that the file name holds."""
    return int(path.stem)


def parse_file(path: pathlib.Path) -> list[list[str]]:
    """Return each data row of one file, as a list of eight columns."""
    rows = []
    pr_number = pull_request_number(path)
    for line in path.read_text(encoding="utf-8").splitlines():
        if not ROW_PATTERN.match(line):
            continue
        cells = [cell.strip() for cell in line.strip().split("|")]
        cells = cells[1:-1]
        if len(cells) != len(COLUMNS):
            raise ValueError(f"{path}: a row does not hold {len(COLUMNS)} columns: {line}")
        rows.append([f"#{pr_number}"] + cells)
    return rows


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    folder = root / "minor_issues"
    files = sorted(folder.glob("*.md"), key=pull_request_number)

    all_rows = []
    for path in files:
        all_rows.extend(parse_file(path))

    header = ["Pull request"] + COLUMNS
    print("| " + " | ".join(header) + " |")
    print("| " + " | ".join(["---"] * len(header)) + " |")
    for row in all_rows:
        print("| " + " | ".join(row) + " |")

    open_count = sum(1 for row in all_rows if row[-1] == "open")
    print(f"Row count: {len(all_rows)}")
    print(f"Open row count: {open_count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
