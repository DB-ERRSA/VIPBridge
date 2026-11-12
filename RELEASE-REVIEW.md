# Repository preparation: VIPBridge

The build uses compile-only local dependencies `libs/ImageFrame-2026.1.2.0.jar` and `libs/HeadDB-6.0.3.jar`. CI needs those files available in the repository or a documented repository-based replacement; confirm redistribution rights before committing them.

## Tracked-file cleanup

The `.gitignore` prevents new IDE and build files from being committed, but **does not remove already tracked files**. After merging the workflow files, run from the repository root:

```bash
git rm -r --cached --ignore-unmatch .idea target build .gradle
```

This untracks generated files while leaving the local copies in place. Commit the resulting staged deletions. Never remove the active `src/` tree.
