# The setup script of a clone of this repository.
#
# The script sets the git hooks path to .githooks. After this step, git
# runs .githooks/pre-commit before each commit. The hook needs gitleaks
# on the PATH. Install gitleaks first, for example with
# winget install gitleaks.

git config core.hooksPath .githooks

Write-Host "The git hooks path is now .githooks."
