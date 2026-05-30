#!/usr/bin/env bash
set -euo pipefail

# ── Output helpers ─────────────────────────────────────────────────────────────
BOLD='\033[1m'
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ok()   { echo -e "${GREEN}  [OK]${NC}   $*"; }
warn() { echo -e "${YELLOW}  [WARN]${NC} $*"; }
fail() { echo -e "${RED}  [FAIL]${NC} $*"; ERRORS=$((ERRORS + 1)); }

ERRORS=0

echo -e "\n${BOLD}=== Maven Central Release ===${NC}\n"

# ── 1. Required tools ──────────────────────────────────────────────────────────
echo -e "${BOLD}Checking required tools...${NC}"

for cmd in mvn java gpg git; do
  if command -v "$cmd" &>/dev/null; then
    ok "$cmd  $(command -v "$cmd")"
  else
    fail "$cmd not found in PATH"
  fi
done

# ── 2. Git state ───────────────────────────────────────────────────────────────
echo -e "\n${BOLD}Checking git state...${NC}"

if ! git rev-parse --is-inside-work-tree &>/dev/null; then
  fail "Not inside a git repository"
else
  ok "Inside a git repository"
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "HEAD")
if [ "$BRANCH" = "HEAD" ]; then
  fail "Detached HEAD state — checkout a branch before releasing"
else
  ok "On branch: $BRANCH"
fi

if [ -z "$(git status --porcelain)" ]; then
  ok "Working directory is clean"
else
  fail "Uncommitted changes detected — commit or stash them first"
fi

if git remote get-url origin &>/dev/null; then
  ok "Remote 'origin' configured"
else
  warn "No remote 'origin' found — release:prepare will not push"
fi

# ── 3. GPG signing key ─────────────────────────────────────────────────────────
echo -e "\n${BOLD}Checking GPG...${NC}"

if gpg --list-secret-keys 2>/dev/null | grep -q "^sec"; then
  ok "GPG secret key found"
else
  fail "No GPG secret key found — import your signing key first"
fi

# ── 4. Maven Central credentials ──────────────────────────────────────────────
echo -e "\n${BOLD}Checking Maven settings...${NC}"

SETTINGS="${MAVEN_SETTINGS_FILE:-$HOME/.m2/settings.xml}"
if [ -f "$SETTINGS" ]; then
  ok "settings.xml found at $SETTINGS"
  if grep -q "<id>central</id>" "$SETTINGS"; then
    ok "Server 'central' configured"
  else
    fail "Server 'central' not found in $SETTINGS — add your Sonatype Central credentials"
  fi
else
  fail "$SETTINGS not found — create it with your Sonatype Central credentials under server id 'central'"
fi

# ── 5. Current version must be a SNAPSHOT ─────────────────────────────────────
echo -e "\n${BOLD}Checking project version...${NC}"

CURRENT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "UNKNOWN")
if [[ "$CURRENT_VERSION" == *"-SNAPSHOT" ]]; then
  ok "Current version: $CURRENT_VERSION"
else
  fail "Current version ($CURRENT_VERSION) is not a SNAPSHOT"
fi

# ── Abort if any check failed ──────────────────────────────────────────────────
if [ "$ERRORS" -gt 0 ]; then
  echo -e "\n${RED}${BOLD}$ERRORS check(s) failed. Fix the issues above before releasing.${NC}\n"
  exit 1
fi

echo -e "\n${GREEN}${BOLD}All checks passed.${NC}\n"

# ── 6. Version prompts ─────────────────────────────────────────────────────────
BASE_VERSION="${CURRENT_VERSION%-SNAPSHOT}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE_VERSION"
DEFAULT_RELEASE="$BASE_VERSION"
DEFAULT_NEXT="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"

read -rp "Release version      [$DEFAULT_RELEASE]: " RELEASE_VERSION
RELEASE_VERSION="${RELEASE_VERSION:-$DEFAULT_RELEASE}"

read -rp "Next dev version     [$DEFAULT_NEXT]: " NEXT_VERSION
NEXT_VERSION="${NEXT_VERSION:-$DEFAULT_NEXT}"

echo ""
echo -e "  Release version  : ${BOLD}$RELEASE_VERSION${NC}"
echo -e "  Next dev version : ${BOLD}$NEXT_VERSION${NC}"
echo ""
read -rp "Proceed? [y/N] " CONFIRM
if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
  echo "Aborted."
  exit 0
fi

# ── 7. Set release version ─────────────────────────────────────────────────────
echo -e "\n${BOLD}Setting release version to $RELEASE_VERSION...${NC}"
mvn versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
ok "POM versions updated to $RELEASE_VERSION"

# ── 8. Build and deploy ────────────────────────────────────────────────────────
echo -e "\n${BOLD}Building and deploying $RELEASE_VERSION...${NC}"
if ! mvn clean deploy -Pdeployment; then
  echo -e "\n${RED}${BOLD}Build failed — reverting POM version changes.${NC}\n"
  git restore .
  exit 1
fi
ok "Artifacts deployed to Maven Central"

# ── 9. Commit, tag, push release ──────────────────────────────────────────────
echo -e "\n${BOLD}Tagging and pushing release...${NC}"
git add -A
git commit -m "[release] $RELEASE_VERSION"
git tag -a "v$RELEASE_VERSION" -m "Release $RELEASE_VERSION"
git push origin "$BRANCH"
git push origin "v$RELEASE_VERSION"
ok "Release $RELEASE_VERSION committed, tagged, and pushed"

# ── 10. Bump to next development version ──────────────────────────────────────
echo -e "\n${BOLD}Bumping to $NEXT_VERSION...${NC}"
mvn versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
git add -A
git commit -m "[release] prepare next development iteration $NEXT_VERSION"
git push origin "$BRANCH"
ok "Next dev version $NEXT_VERSION committed and pushed"

echo -e "\n${GREEN}${BOLD}Release $RELEASE_VERSION successfully published to Maven Central!${NC}\n"