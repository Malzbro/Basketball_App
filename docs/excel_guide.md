# Making the graphs yourself in Excel

Step by step, using your session `session_20261030_163316`. Written for Excel 365 /
Excel 2019+ on Windows. Parts 2–5 rebuild the four figures in [reading_the_plots.md](reading_the_plots.md).
What each graph means is explained in
[reading_the_plots.md](reading_the_plots.md).

Your raw values will be a little higher and jumpier than in the Python plots
(e.g. a flick peak of 12.1 instead of 11.5), because those are smoothed over a few
samples. The shapes are the same.

---

## Part 0: Download the files

On GitHub, switch to the branch **`claude/blissful-gates-h9rls5`**, open the
**`recordings`** folder, and download these 4 files (click the file, then the
**Download raw file** button ⤓ at the top right):

| File | Used for |
|---|---|
| `session_20261030_163316_raw.zip` | the sensor data (parts 1–3) |
| `session_20261030_163316_events.csv` | your Make/Miss taps (part 3) |
| `session_20261030_163316_shot_features.csv` | numbers for each of your 142 shots (part 4) |
| `session_20261030_163316_other_arm_raises.csv` | numbers for the 75 other arm raises (part 4) |

Right-click the zip → **Extract All** → **Extract**. You now have
`session_20261030_163316_raw.csv`.

---

## Part 1: Open the data and add the three measures

1. Double-click `session_20261030_163316_raw.csv`. It opens in Excel (259,518 rows, so give
   it a few seconds).
2. **Save it as a workbook right away:** File → Save As → choose **Excel Workbook (*.xlsx)**
   → name it `my_session.xlsx`. (A CSV can't keep formulas or charts.)
3. Check the columns: **A** `t_ns`, **B** `t_rel_s` (seconds since Start), **C–E** `ax ay az`,
   **F–H** `gx gy gz`, **I–K** `grav_x grav_y grav_z`, **L–O** quaternion (ignore those).
4. Type these headers: **P1** `angle_deg`, **Q1** `gyro`, **R1** `accel`.
5. Type these formulas in row 2:

   | Cell | Formula | Meaning |
   |---|---|---|
   | P2 | `=DEGREES(ASIN(I2/SQRT(I2^2+J2^2+K2^2)))` | forearm angle (negative = arm up) |
   | Q2 | `=SQRT(F2^2+G2^2+H2^2)` | wrist rotation speed, rad/s |
   | R2 | `=SQRT(C2^2+D2^2+E2^2)` | acceleration, m/s² (9.8 when still) |

6. Select **P2:R2**, then **double-click the small square** at the bottom-right corner of the
   selection (the fill handle). Excel copies the formulas down all 259,518 rows.
7. **Check:** row 2 should show about `3.82`, `0.08`, `9.79`. Row **28693** (time 287.40 s,
   the release in the example shot) should show about `-62.29`, `11.23`, `71.14`.
   Jump there by typing `P28693` in the **Name Box** (the little box left of the formula
   bar) and pressing Enter.
8. Save (Ctrl+S).

---

## Part 2: One shot, step by step (like `shot_anatomy.png`)

The example shot is a miss with its release at **287.407 s**, which is rows **28494–28993**
(285.4 s to 290.4 s, i.e. 2 s before to 3 s after).

**Copy the shot to its own sheet**

1. Click the **+** next to the sheet tabs to add a sheet; double-click its tab and name it `Shot`.
2. Go back to the data sheet. In the **Name Box** type `B28494:B28993,P28494:R28993` and press
   Enter (this selects time plus your three columns).
3. Press **Ctrl+C**, go to the `Shot` sheet, click **A2**, and press **Ctrl+Alt+V** → choose
   **Values** → OK. (Values, not formulas, otherwise the formulas would point at the wrong rows.)
4. Type headers in row 1: **A1** `time_s`, **B1** `angle_deg`, **C1** `gyro`, **D1** `accel`.
   **Check:** row **201** is the release and should read about `287.3962`, `-62.29`, `11.23`, `71.14`.

**Add helper columns**

5. **E1** `time_from_release`, **E2** `=A2-287.407`
6. **F1** `arm_line`, **F2** `-45`   (the arm counts as raised below −45°)
7. **G1** `flick_line`, **G2** `8`   (the flick must reach 8 rad/s)
8. **H1** `spike_line`, **H2** `20`  (the spike must reach 20 m/s²)
9. Select **E2:H2** and double-click the fill handle to fill down to row 501.
10. The tap: **J1** `tap_x`, **K1** `tap_y`; **J2** `1.806`, **J3** `1.806`, **K2** `-80`,
    **K3** `80`. (You tapped MISS 1.806 s after the release; two points make a vertical line.)

**Chart 1: forearm angle**

11. Click an **empty cell** away from your data (e.g. **M2**), so Excel doesn't guess which
    columns to use.
12. **Insert** → in Charts, click the **X Y (Scatter)** icon → **Scatter with Straight Lines**
    (the icon with lines and no dots). An empty chart appears.
    (Use Scatter, not "Line": a Line chart ignores the time values and spaces points evenly.)
13. Add the angle: right-click the chart → **Select Data** → **Add**. In each box, delete what's
    there and select the cells with the mouse:
    * Series name: `forearm angle`
    * Series X values: **E2:E501** (time from release)
    * Series Y values: **B2:B501** (angle)
    * OK. (Don't click OK on the outer window yet.)

    > If your line is a straight diagonal from −2 to 3 and the x axis runs 0–500, Excel used
    > the wrong columns: in **Select Data**, click that series → **Edit** and set X and Y as above.
14. Add the −45° line: still in **Select Data** → **Add**: right-click the chart → **Select Data** → **Add**:
    * Series name: `arm raised below -45°`
    * Series X values: select **E2:E501**
    * Series Y values: delete what's there and select **F2:F501**
    * OK.
15. Add your tap: **Add** again, name `you tapped MISS`, X = **J2:J3**, Y = **K2:K3** → OK → OK.
16. Make the threshold line dashed: click it → right-click → **Format Data Series** → paint-can
    icon → **Line** → **Dash type**: dashes; color: gray.
17. Fix the x axis: right-click the numbers under the chart → **Format Axis** → Bounds:
    Minimum `-2`, Maximum `3`.
18. If any line shows fat dots: click it → right-click → **Format Data Series** → paint-can →
    **Marker** → **None**.
19. Click the chart title and type `Forearm angle (°), negative = arm up`.

**Charts 2 and 3: wrist rotation and acceleration**

20. Same steps with X = **E** and Y = **C** (gyro) and the line in **G**: title `Wrist rotation speed (rad/s)`.
21. Same steps with X = **E** and Y = **D** (accel) and the line in **H**: title `Acceleration (m/s²)`.
    For the tap line in these two charts, use Y values of 0 and 15, or 0 and 80, so it fits.
22. Stack the three charts on top of each other, with the same width, so the time lines up.

**What you should see:** the angle drops below −45° just before 0 and stays there until
about +1.3 s (the follow-through). At 0 the rotation speed peaks around 11–12 rad/s and the
acceleration jumps to about 70 m/s². That's one shot.

---

## Part 3: The first 3 minutes (like `overview_zoom.png`)

180 seconds is rows **2 to 17970**.

1. On the data sheet, select **B1:B17970**, hold **Ctrl**, select **P1:P17970**.
2. **Insert** → **Scatter with Straight Lines**. Title: `Forearm angle, first 3 minutes`.
3. Repeat with **Q1:Q17970** and **R1:R17970** for the other two charts.
4. Add your taps: open `session_20261030_163316_events.csv`. Right-click its sheet tab →
   **Move or Copy** → To book: `my_session.xlsx` → OK. In that sheet type **F1** `marker`,
   **F2** `-80` and fill down to **F21** (the 20 taps in the first 3 minutes are rows 2–21).
5. On the angle chart: right-click → **Select Data** → **Add**: name `taps`, X = the events
   sheet's **B2:B21** (`t_rel_s`), Y = **F2:F21** → OK.
6. Make them dots only: click the new series → right-click → **Change Series Chart Type** →
   choose **Scatter** (markers only) for `taps`.

**What you should see:** each shot is a dip of the angle line below −45°, about 2 s before a
tap dot. The flat stretch around 30–45 s is the watch resting.

Charting all 259,518 rows at once works, but it's slow and unreadable. Zoom to a few
minutes at a time instead: rows = seconds × 100 + 2, roughly.

---

## Part 4: Where the thresholds come from (like `feature_histograms.png`)

Each row in these two files is one arm raise. Columns: **B** `peak_gyro` (flick),
**C** `release_accel` (spike), **D** `hold_s` (follow-through).

**Quick version (one group)**

1. Open `session_20261030_163316_shot_features.csv`.
2. Click the column letter **D** (`hold_s`).
3. **Insert** → **Insert Statistic Chart** (the histogram icon) → **Histogram**.
4. Right-click the numbers under the chart → **Format Axis** → **Bin width**: `0.1`.

You'll see most of your shots held their arm up for 1.2–1.5 s.

**Comparing shots with other arm raises (the useful version)**

1. Move both feature files into `my_session.xlsx` (right-click each tab → **Move or Copy** →
   To book: `my_session.xlsx`). Rename the tabs `shots` and `other`.
2. Add a sheet named `Hist`. Type **A1** `bin_start`, **B1** `shots`, **C1** `other raises`.
3. **A2** `0`, **A3** `=A2+0.1`, then fill A3 down to **A34** (bins 0, 0.1, … 3.2 s).
4. **B2:** `=COUNTIFS(shots!D:D,">="&A2,shots!D:D,"<"&A2+0.1)`
5. **C2:** `=COUNTIFS(other!D:D,">="&A2,other!D:D,"<"&A2+0.1)`
6. Select **B2:C2**, fill down to row 34.
7. Select **A1:C34** → **Insert** → **Clustered Column** chart.
   If Excel plots `bin_start` as a third set of bars: right-click → **Select Data** → remove
   `bin_start` from the series → **Horizontal (Category) Axis Labels** → Edit → select **A2:A34**.
8. Right-click a bar → **Format Data Series** → **Gap Width** `0` so bars touch.

**What you should see:** the `shots` bars cluster around 1.3 s, while most `other raises` are
below 0.3 s. That gap is why the detector requires a hold of at least **0.3 s**.

To do the flick or the spike instead, change `D:D` to `B:B` (flick; bins of 1, from 0 to 20)
or `C:C` (spike; bins of 5, from 0 to 120), and change the `0.1` step to match.

---

## Part 5: All your shots on top of each other (like `shot_snippets.png`)

Each line in this chart is one shot: the 3 seconds around its release (1.5 s before to
1.5 s after), shifted so every release sits at 0. Instead of copying 40 blocks of rows
by hand, formulas look them up for you.

**Prepare (once)**

1. Rename your data sheet (the one with columns P, Q, R from Part 1) to `Data`:
   double-click its tab, type `Data`, Enter.
2. Make sure `session_20261030_163316_shot_features.csv` is in the same workbook as a sheet
   named `shots` (Part 4, step 1: right-click its tab → **Move or Copy** → To book:
   `my_session.xlsx`, then rename). Column **A** of `shots` is each shot's release time.

**Build the lookup table**

3. Add a sheet named `SnipAngle`.
4. **A1:** `offset_s`. **A2:** `=(ROW()-152)/100`, then fill down to **A302**.
   Column A now runs −1.5, −1.49, … 0 (row 152) … 1.5: the time relative to the release.
5. **B1:** `=INDEX(shots!$A$2:$A$41,COLUMN()-1)`, then fill right to **AO1**.
   Row 1 now holds the release times of your first 40 shots, one per column (5.06,
   47.312, …).
6. **B2:** `=INDEX(Data!$P:$P,MATCH(B$1+$A2,Data!$B:$B,1))`
   This means: *find the row in Data whose time is closest to (release + offset), and give
   me its angle.* The `$` signs keep it pointing at the right row and column when copied.
7. Select **B2**, copy it (**Ctrl+C**), select **B2:AO302**, and paste (**Ctrl+V**). That's
   40 columns × 301 rows; it may take a few seconds to calculate.
8. **Check:** **B152** (shot 1 at its release) ≈ `-64.97`, **B2** ≈ `18.97`,
   **C152** (shot 2) ≈ `-60.02`.

**Make the chart**

9. Select **A1:AO302** → **Insert** → **X Y (Scatter)** → **Scatter with Straight Lines**.
   Excel uses column A as time and makes one line per shot: 40 lines.
10. Too colorful? Click the chart → **Chart Design** → **Change Colors** → pick a
    **Monochromatic** palette, so all lines are shades of one color.
11. Delete the legend (click it, press **Delete**): 40 entries aren't useful.
12. Right-click the x-axis numbers → **Format Axis** → Minimum `-1.5`, Maximum `1.5`.
13. Title: `Forearm angle, 40 shots lined up at the release`.

**The other two rows**

14. Right-click the `SnipAngle` tab → **Move or Copy** → tick **Create a copy** → OK.
    Rename the copy `SnipGyro`.
15. On `SnipGyro`, select **B2:AO302**, press **Ctrl+H** (Find & Replace), Find
    `Data!$P:$P`, Replace with `Data!$Q:$Q` → **Replace All**. The chart on that sheet now
    shows wrist rotation (check: **B152** ≈ `9.54`). Retitle it.
16. Same again for acceleration: copy to `SnipAccel`, replace with `Data!$R:$R`.

**Coloring makes vs misses (optional)**

Excel colors each line separately, so this takes one click per line:
check row 2 of `shots` onward, column `label` (I), to see which shot is which. Click a line →
right-click → **Format Data Series** → **Line** → **Color**. Shots 1–40 are 18 makes and
22 misses. A quicker alternative: two charts, one with only make columns and one with only
miss columns.

**What you should see:** all 40 lines follow nearly the same path: the gather (rotation bump
around −0.5 s), the dip just before 0, the release at 0, and the arm held at about −60°
until about +1 s. The tighter the bundle, the more consistent your shot.

---

## Tips

* **Numbers look like `4.03E+14` in column A?** That's `t_ns` (nanoseconds). You don't need it;
  use column B (seconds).
* **Everything landed in column A?** Your Windows uses commas for decimals. Instead of
  double-clicking, use **Data** → **From Text/CSV** → pick the file → set the delimiter to
  **Comma** → **Load**.
* **Excel is slow?** Formulas → Calculation Options → **Manual** while you work, then press
  **F9** to recalculate.
* **Smoother lines (like the Python plots):** in **S4** type `=AVERAGE(Q2:Q4)` and fill down:
  each row becomes the average of itself and the 2 rows before it (rotation, 3 samples).
  For the angle, **T6** `=AVERAGE(P2:P6)` (5 samples). Chart S or T instead of Q or P.
