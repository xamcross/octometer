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

# A row splits on each "|" that marks a column edge. A "\|" inside a cell
# is not a column edge (issue #449): the lookbehind skips it, so the
# escape stays one literal pipe inside its own cell.
CELL_SPLIT_PATTERN = re.compile(r"(?<!\\)\|")


class RowFormatError(ValueError):
    """A row of a minor_issues file does not hold 7 columns."""

    def __init__(self, path: pathlib.Path, line: str, cell_count: int):
        self.path = path
        self.line = line
        self.cell_count = cell_count
        super().__init__(
            f"{path}: a row holds {cell_count} columns, not {len(COLUMNS)}: {line}"
        )


def pull_request_number(path: pathlib.Path) -> int:
    """Return the pull request number that the file name holds."""
    return int(path.stem)


def split_row_cells(line: str) -> list[str]:
    """Return each cell of one table row, with no outer empty cell.

    A "\\|" inside a cell stays one literal pipe; it marks no column edge.
    """
    parts = CELL_SPLIT_PATTERN.split(line.strip())
    cells = [cell.strip() for cell in parts]
    return cells[1:-1]


def parse_file(path: pathlib.Path) -> list[list[str]]:
    """Return each data row of one file, as a list of eight columns."""
    rows = []
    pr_number = pull_request_number(path)
    for line in path.read_text(encoding="utf-8").splitlines():
        if not ROW_PATTERN.match(line):
            continue
        cells = split_row_cells(line)
        if len(cells) != len(COLUMNS):
            raise RowFormatError(path, line, len(cells))
        rows.append([f"#{pr_number}"] + cells)
    return rows


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    folder = root / "minor_issues"
    files = sorted(folder.glob("*.md"), key=pull_request_number)

    # The format check (issue #449): each row of each file must hold 7
    # columns. A bad row stops the script here, with a clear message and
    # a non-zero exit code, so the pull request review catches it too.
    all_rows = []
    for path in files:
        try:
            all_rows.extend(parse_file(path))
        except RowFormatError as error:
            print(f"minor_issues_table: {error}", file=sys.stderr)
            return 1

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
