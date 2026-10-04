# Renders the project logo (512px icon, 128px mod icon, 1280x400 banner) from the altar's block textures.
# Usage: powershell -ExecutionPolicy Bypass -File tools\make_logo.ps1
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$tex = Join-Path $root "src\main\resources\assets\offering_altar\textures\block"
$outDir = Join-Path $root "release\logo"
New-Item -ItemType Directory -Force $outDir | Out-Null
$top = [System.Drawing.Bitmap]::FromFile("$tex\offering_altar_top.png")
$side = [System.Drawing.Bitmap]::FromFile("$tex\offering_altar_side.png")

function Shade([float]$k) {
    $m = New-Object System.Drawing.Imaging.ColorMatrix
    $m.Matrix00 = $k; $m.Matrix11 = $k; $m.Matrix22 = $k; $m.Matrix33 = 1; $m.Matrix44 = 1
    $a = New-Object System.Drawing.Imaging.ImageAttributes
    $a.SetColorMatrix($m)
    return $a
}
function P($x, $y) { New-Object System.Drawing.PointF([float]$x, [float]$y) }

# Isometric cube: top face rhombus plus left/right faces, nearest-neighbour so texels stay crisp.
function Draw-Altar($g, [float]$cx, [float]$cy, [float]$w) {
    $h = $w / 2; $H = $w * 1.0
    $T = P $cx ($cy - $h); $R = P ($cx + $w) $cy; $B = P $cx ($cy + $h); $L = P ($cx - $w) $cy
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $src = New-Object System.Drawing.Rectangle 0, 0, 16, 16
    $g.DrawImage($side, [System.Drawing.PointF[]]@($L, $B, (P $L.X ($L.Y + $H))), $src, [System.Drawing.GraphicsUnit]::Pixel, (Shade 0.78))
    $g.DrawImage($side, [System.Drawing.PointF[]]@($B, $R, (P $B.X ($B.Y + $H))), $src, [System.Drawing.GraphicsUnit]::Pixel, (Shade 0.58))
    $g.DrawImage($top, [System.Drawing.PointF[]]@($T, $R, $L), $src, [System.Drawing.GraphicsUnit]::Pixel, (Shade 1.0))
}

function Draw-Scene($g, [float]$cx, [float]$cy, [float]$radius, [float]$cubeW, [int]$seed) {
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    # Three promised lands as wedges of a disc: desert, forest, snow
    $rect = New-Object System.Drawing.RectangleF ($cx - $radius), ($cy - $radius), (2 * $radius), (2 * $radius)
    $lands = @(@(219, 196, 120), @(84, 150, 62), @(232, 240, 248))
    for ($i = 0; $i -lt 3; $i++) {
        $c = $lands[$i]
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, $c[0], $c[1], $c[2]))
        $g.FillPie($brush, [float]$rect.X, [float]$rect.Y, [float]$rect.Width, [float]$rect.Height, [float](-90 + $i * 120), [float]120)
    }
    # Dark rim and wedge seams
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 24, 20, 34)), ($radius * 0.05)
    $g.DrawEllipse($pen, $rect)
    $seam = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(200, 24, 20, 34)), ($radius * 0.025)
    for ($i = 0; $i -lt 3; $i++) {
        $a = (-90 + $i * 120) * [Math]::PI / 180
        $g.DrawLine($seam, $cx, $cy, [float]($cx + [Math]::Cos($a) * $radius), [float]($cy + [Math]::Sin($a) * $radius))
    }
    # Violet glow and the offering beam
    $glowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $gr = $radius * 0.75
    $glowPath.AddEllipse([float]($cx - $gr), [float]($cy - $gr), [float](2 * $gr), [float](2 * $gr))
    $glow = New-Object System.Drawing.Drawing2D.PathGradientBrush $glowPath
    $glow.CenterColor = [System.Drawing.Color]::FromArgb(170, 150, 80, 255)
    $glow.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 150, 80, 255))
    $g.FillPath($glow, $glowPath)
    $beamW = $cubeW * 0.5
    $beamRect = New-Object System.Drawing.RectangleF ($cx - $beamW / 2), ($cy - $radius * 1.05), $beamW, ($radius * 1.0)
    $beam = New-Object System.Drawing.Drawing2D.LinearGradientBrush $beamRect, ([System.Drawing.Color]::FromArgb(0, 210, 160, 255)), ([System.Drawing.Color]::FromArgb(200, 225, 190, 255)), ([System.Drawing.Drawing2D.LinearGradientMode]::Vertical)
    $g.FillRectangle($beam, $beamRect)
    Draw-Altar $g $cx ($cy - $cubeW * 0.25) $cubeW
    # Pixel sparks
    $rnd = New-Object System.Random $seed
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
    for ($i = 0; $i -lt 18; $i++) {
        $s = [float]($radius * 0.035)
        $x = $cx + ($rnd.NextDouble() - 0.5) * $radius * 1.3
        $y = $cy - $rnd.NextDouble() * $radius * 1.1
        $col = if ($i % 3 -eq 0) { [System.Drawing.Color]::FromArgb(230, 240, 200, 90) } else { [System.Drawing.Color]::FromArgb(220, 200, 150, 255) }
        $g.FillRectangle((New-Object System.Drawing.SolidBrush $col), [float]$x, [float]$y, $s, $s)
    }
}

function Fill-Background($g, [int]$w, [int]$h) {
    $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(255, 30, 22, 52)), ([System.Drawing.Color]::FromArgb(255, 12, 14, 28)), ([System.Drawing.Drawing2D.LinearGradientMode]::Vertical)
    $g.FillRectangle($bg, $rect)
}

# Square icon
$icon = New-Object System.Drawing.Bitmap 512, 512
$g = [System.Drawing.Graphics]::FromImage($icon)
Fill-Background $g 512 512
Draw-Scene $g 256 296 200 74 3
$g.Dispose()
$icon.Save("$outDir\icon_512.png", [System.Drawing.Imaging.ImageFormat]::Png)

$small = New-Object System.Drawing.Bitmap 128, 128
$g = [System.Drawing.Graphics]::FromImage($small)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($icon, 0, 0, 128, 128)
$g.Dispose()
$small.Save((Join-Path $root "src\main\resources\assets\offering_altar\icon.png"), [System.Drawing.Imaging.ImageFormat]::Png)

# Banner: scene on the left, blocky title rendered small and scaled up for a pixel look
$banner = New-Object System.Drawing.Bitmap 1280, 400
$g = [System.Drawing.Graphics]::FromImage($banner)
Fill-Background $g 1280 400
Draw-Scene $g 220 236 160 60 5

$titleSmall = New-Object System.Drawing.Bitmap 220, 40
$tg = [System.Drawing.Graphics]::FromImage($titleSmall)
$tg.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
$font = New-Object System.Drawing.Font "Arial Black", 18, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$tg.DrawString("Offering Altar", $font, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 60, 40, 10))), 2, 4)
$tg.DrawString("Offering Altar", $font, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 236, 196, 80))), 0, 2)
$tg.Dispose()
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.DrawImage($titleSmall, 430, 90, 880, 160)

$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$sub = New-Object System.Drawing.Font "Malgun Gothic", 30, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString("제물의 제단  ·  Choose the land beyond", $sub, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 205, 175, 255))), 440, 262)
$g.Dispose()
$banner.Save("$outDir\banner_1280x400.png", [System.Drawing.Imaging.ImageFormat]::Png)

$top.Dispose(); $side.Dispose()
Write-Output "logo written to $outDir"
