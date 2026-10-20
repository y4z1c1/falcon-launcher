# Tools

## `planstudio/build_home_plan.py`

Builds the lights page's floor plan (`home_plan.json`) from a [PlanStudio](https://github.com/y4z1c1/planstudio)
project: the Polycam scan (`.glb`) and the furnished layout (`.planstudio.json`).

```bash
python3 tools/planstudio/build_home_plan.py scan.glb layout.planstudio.json app/src/personal/res/raw/home_plan.json
```

It reads, with no dependencies (`glb.py` is a minimal glTF reader):

- **rooms:** outlines of the scan's `Floor_*` meshes, named with PlanStudio's room names;
- **walls and doors:** footprints of `Wall_*` / `Joint_*` / `Door_*` nodes;
- **fixtures:** scanned objects (toilet, sink, oven, cabinets…), with PlanStudio's moves and deletions applied;
- **furniture and arched windows** from the layout.

Furniture footprints come from the catalog name when it carries dimensions (`"Dolap 46×113"`, in cm), or from the
`SIZES` table for catalog items without them. Add your own items there. The app draws matching simple models
for sofas, beds, tables, chairs, wardrobes/cabinets, washers, fridges, TVs, ovens, sinks and toilets; anything
else becomes a plain block.

## `make_sample_plan.py`

Regenerates the made-up sample apartment that ships with the app (`app/src/main/res/raw/home_plan.json`).
