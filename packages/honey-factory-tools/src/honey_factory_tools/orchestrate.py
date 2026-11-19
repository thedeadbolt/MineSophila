"""Generate one dataset on several game instances at once (M12-1, ``generate``).

Each instance generates a slice of the configuration's scene groups (``output.slice``) in a
world of its own, named ``<dataset name>-i<index>``, with its own game folder. The job
waits for every instance, then merges the slices into the dataset a single run would write
(:mod:`honey_factory_tools.merge`). A failed instance fails the job, naming its slice; the
finished slices stay on disk and nothing is merged. With one instance the dataset is
generated whole, without slicing or merging.
"""

import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from honey_factory_tools.merge import MergeError, merge
from honey_factory_tools.schemas import Schemas

#: Where instance ``i`` keeps its game folder, relative to the mod's project.
RUN_DIR = "build/run/instance-{index}"


class GenerateError(Exception):
    """An instance failed or the slices could not be merged; the finished slices remain."""


@dataclass(frozen=True)
class Job:
    """One instance's work: its configuration, world and game folder, and its log."""

    index: int
    config: Path
    world: str
    world_seed: int
    run_dir: str
    file: Path
    log: Path


class Process(Protocol):
    def wait(self) -> int: ...


class Launcher(Protocol):
    def start(self, job: Job) -> Process: ...


class GradleLauncher:
    """Starts a game client per job through the mod's ``runClientGameTest``, running only
    ``GenerationJobTest``; separate game folders let the clients run side by side."""

    def __init__(self, project: Path) -> None:
        self.project = project

    def start(self, job: Job) -> Process:
        log = job.log.open("w", encoding="utf-8")
        return subprocess.Popen(
            [
                str(self.project / "gradlew"),
                "runClientGameTest",
                "-Dhoneyfactory.gametests=GenerationJob",
                f"-Dhoneyfactory.job={job.file}",
                f"-Dhoneyfactory.runDir={job.run_dir}",
            ],
            cwd=self.project,
            stdout=log,
            stderr=subprocess.STDOUT,
        )


def generate(
    config: Path,
    instances: int,
    root: Path,
    launcher: Launcher,
    schemas: Schemas,
    world_seed: int | None = None,
) -> Path:
    """Generate ``config`` on ``instances`` game instances into ``root``; return the dataset."""
    if instances < 1:
        raise GenerateError("--instances must be at least 1")
    settings: dict[str, Any] = json.loads(config.read_text(encoding="utf-8"))
    name: str = settings["dataset"]["name"]
    seed = world_seed if world_seed is not None else int(settings["dataset"]["seed"])
    root = root.resolve()
    jobs_dir = root / "jobs"
    jobs_dir.mkdir(parents=True, exist_ok=True)
    slices_root = root if instances == 1 else root / "slices"

    jobs: list[Job] = []
    for index in range(instances):
        world = f"{name}-i{index}"
        output: dict[str, Any] = dict(settings.get("output", {}))
        output["root"] = str(slices_root)
        if instances > 1:
            output["slice"] = {"index": index, "count": instances, "world": world}
        else:
            output.pop("slice", None)
        part = dict(settings, output=output)
        part_config = jobs_dir / f"{name}-i{index}.config.json"
        part_config.write_text(json.dumps(part, indent=2) + "\n", encoding="utf-8")
        job = Job(
            index=index,
            config=part_config,
            world=world,
            world_seed=seed,
            run_dir=RUN_DIR.format(index=index),
            file=jobs_dir / f"{name}-i{index}.job.json",
            log=jobs_dir / f"{name}-i{index}.log",
        )
        job.file.write_text(
            json.dumps(
                {
                    "config": str(job.config),
                    "world": job.world,
                    "world_seed": job.world_seed,
                    "output_root": str(root),
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        jobs.append(job)

    processes = [(job, launcher.start(job)) for job in jobs]
    failed: list[str] = []
    for job, process in processes:
        code = process.wait()
        print(f"instance {job.index} ({job.world}) exited with {code}", file=sys.stderr)
        if code != 0:
            failed.append(f"slice {job.index} (exit {code}, log {job.log})")
    if failed:
        raise GenerateError("instances failed: " + "; ".join(failed))

    if instances == 1:
        made = [path for path in slices_root.iterdir() if (path / "dataset.json").is_file()]
        made = [path for path in made if path.name.startswith(name + "-")]
        if len(made) != 1:
            raise GenerateError(
                f"expected one dataset of {name} in {slices_root}, found {len(made)}"
            )
        return made[0]

    parts = sorted(slices_root.glob(f"{name}-*.slice-*-of-{instances}"))
    try:
        return merge(parts, root, schemas)
    except MergeError as error:
        raise GenerateError(f"merging the slices failed: {error}") from error
