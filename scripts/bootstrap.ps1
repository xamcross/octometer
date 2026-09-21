# The setup script of a clone of this repository.
#
# The script sets the git hooks path to .githooks. After this step, git
# runs .githooks/pre-commit before each commit. Run this script one time
# in each clone to turn the pre-commit hook on.

if (-not (Get-Command gitleaks -ErrorAction SilentlyContinue)) {
    Write-Warning "gitleaks is not on the PATH. The pre-commit hook needs it."
    Write-Warning "Install it, for example with: winget install gitleaks"
}

git config core.hooksPath .githooks

Write-Host "The git hooks path is now .githooks."
