#!/usr/bin/env bash
# Merge every local feature/* branch into the current personal/master branch.
# The script stops at the first merge conflict so it can be resolved manually.

set -euo pipefail

target_branch="personal/master"
current_branch="$(git branch --show-current)"

if [[ "$current_branch" != "$target_branch" ]]; then
    echo "Error: check out $target_branch before running this script (currently: ${current_branch:-detached HEAD})." >&2
    exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Error: tracked changes are present. Commit or stash them first." >&2
    exit 1
fi

mapfile -t feature_branches < <(git for-each-ref --format='%(refname:short)' refs/heads/feature/ | sort)

if (( ${#feature_branches[@]} == 0 )); then
    echo "No local feature/* branches found."
    exit 0
fi

for branch in "${feature_branches[@]}"; do
    echo "Merging $branch into $target_branch..."
    if ! git merge --no-ff "$branch" -m "Merge branch '$branch'"; then
        echo >&2
        echo "Merge conflict while merging $branch." >&2
        echo "Resolve it, then run 'git commit' and re-run this script to continue." >&2
        echo "Or run 'git merge --abort' to cancel the current merge." >&2
        exit 1
    fi
done

echo "Merged all local feature/* branches into $target_branch."
