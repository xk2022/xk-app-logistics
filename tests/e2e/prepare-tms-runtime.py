"""Copy only the explicit test launcher and synthetic policy onto the isolated runtime classpath."""

from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = Path("com/xksystem/logistics/support")
SOURCE = ROOT / "backend/target/test-classes" / PACKAGE
DESTINATION = ROOT / "artifacts/tms-e2e-runtime"


def main():
    exact = [SOURCE / name for name in (
        "TmsE2eApplication.class", "SyntheticDispatchPolicy.class",
        "SyntheticEvidencePolicy.class", "SyntheticEvidenceStorage.class",
        "SyntheticExceptionPolicy.class", "SyntheticCompletionPolicy.class",
    )]
    dispatch_inner = sorted(SOURCE.glob("SyntheticDispatchPolicy$*.class"))
    evidence_inner = sorted(SOURCE.glob("SyntheticEvidencePolicy$*.class"))
    exception_inner = sorted(SOURCE.glob("SyntheticExceptionPolicy$*.class"))
    completion_inner = sorted(SOURCE.glob("SyntheticCompletionPolicy$*.class"))
    if not all(path.is_file() for path in exact) or not dispatch_inner or not evidence_inner or not exception_inner or not completion_inner:
        raise SystemExit("Compile the isolated TMS test launcher and policy before preparing its runtime")
    files = exact + dispatch_inner + evidence_inner + exception_inner + completion_inner
    # Never copy all test classes, the @TestConfiguration wrapper, fixtures, or JUnit libraries.
    if DESTINATION.exists():
        shutil.rmtree(DESTINATION)
    target = DESTINATION / PACKAGE
    target.mkdir(parents=True)
    # README deliberately uses umask 077 for private fixture credentials. This
    # separate public classpath must remain traversable by container UID 10001.
    DESTINATION.chmod(0o755)
    for directory in DESTINATION.rglob("*"):
        if directory.is_dir():
            directory.chmod(0o755)
    for path in files:
        shutil.copyfile(path, target / path.name)
        (target / path.name).chmod(0o644)
    print(f"Prepared {len(files)} explicitly allowed test-only runtime classes; production JAR unchanged")


if __name__ == "__main__":
    main()
