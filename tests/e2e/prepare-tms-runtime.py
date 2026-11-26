"""Copy only the explicit test launcher and synthetic policy onto the isolated runtime classpath."""

from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = Path("com/xksystem/logistics/support")
SOURCE = ROOT / "backend/target/test-classes" / PACKAGE
DESTINATION = ROOT / "artifacts/tms-e2e-runtime"


def main():
    exact = [SOURCE / "TmsE2eApplication.class", SOURCE / "SyntheticDispatchPolicy.class"]
    inner = sorted(SOURCE.glob("SyntheticDispatchPolicy$*.class"))
    if not all(path.is_file() for path in exact) or not inner:
        raise SystemExit("Compile the isolated TMS test launcher and policy before preparing its runtime")
    files = exact + inner
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
