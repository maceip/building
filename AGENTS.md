# Repository instructions

## Task completion: commit, push, assess, report

The owner gives standing authorization for ordinary commits and task-branch pushes after completing a feature or task. Do not leave completed changes uncommitted merely because the owner did not repeat a commit/push request.

1. **Use a task branch.** Create a branch for the specific task before editing. In an existing task-specific worktree, use that worktree's task branch. Never commit directly to `main`. Preserve unrelated changes and unpublished commits; use an isolated branch/worktree when needed instead of resetting, stashing, or publishing someone else's work.
2. **Verify and commit immediately.** Review the task diff, run relevant available checks, and commit completed task-owned changes before the final handoff. Stage explicit files or hunks. Exclude unrelated work, secrets, credentials, generated build artifacts and machine-local state. A task with no versioned changes needs no empty commit.
3. **Push the branch.** Push to the intended remote and set the branch's upstream. Verify success. This authorization does not include force-pushes, history rewriting, changing repository visibility, production deployment, or publishing unrelated history.
4. **Assess merging into current remote main.** Fetch `origin/main` and non-destructively check merging the pushed task commit into that exact fetched commit, using `git merge-tree --write-tree` or an equivalent isolated check. Do not perform the merge in the active checkout or modify either branch. Report the number of unique conflicted files/paths and list them; report conflict hunks separately if counted. Record both checked commit IDs. Zero conflicts applies only to the checked snapshot.
5. **Report to the owner.** Include the branch, commit ID, push result/remote, relevant checks, and merge-conflict count/paths. If any step is blocked, state exactly what succeeded, what did not, and why. Never present a failed push or incomplete verification as delivered work.

Do not automatically merge into `main`. Do not bypass denied actions, push protection, credentials requirements or branch rules. Preserve the working tree on failure. A broader request to finish a task does not authorize including unrelated edits.
