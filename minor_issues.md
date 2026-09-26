# Minor Issues

The owner decided on 2026-09-22 that a review keeps each MINOR finding in this store. A row never leaves the store, also after the production release. A later fix sets the status of that row to `fixed in #N`. No pull request fixes a MINOR finding of its own review.

The owner decided on 2026-09-26 to move each row into one file per pull request. This way, two parallel pull requests never conflict on one file.

## Where the rows are

- Each pull request has its own file, under `minor_issues/`.
- The file name is the pull request number, for example `minor_issues/163.md`.
- Each file holds a table with these columns, in this order: Date, Issue, Reviewer role, File and line, Finding, Suggested fix, Status.

## How to add a row

- Append the row to the file of your pull request. Create the file when it does not exist.
- Add one row for each MINOR finding that stays open.
- Never remove a row from a file.
- Set the status of a row to `fixed in #N` when a later pull request fixes that finding.

Run `python tools/minor_issues_table.py` to print the whole table, merged from every file.
