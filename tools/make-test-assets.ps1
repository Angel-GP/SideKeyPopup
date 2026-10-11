# =============================================================================
#  Generate test assets for device / emulator verification:
#    build\test\photo.png  - 900x600 static image with obvious green corners
#    build\test\anim.gif   - 3-frame animated GIF that loops forever
#
#  NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads .ps1 files
#  without a BOM as ANSI (GBK on zh-CN systems) and mangles non-ASCII text.
# =============================================================================

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $root 'build\test'
New-Item -ItemType Directory -Force -Path $out | Out-Null

Add-Type -AssemblyName System.Drawing

# ------------------------------------------------------------------ photo.png
$w = 900; $h = 600
$bmp = [System.Drawing.Bitmap]::new($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$rect = [System.Drawing.Rectangle]::new(0, 0, $w, $h)
$c1 = [System.Drawing.Color]::FromArgb(255, 255, 90, 40)
$c2 = [System.Drawing.Color]::FromArgb(255, 40, 90, 255)
$brush = [System.Drawing.Drawing2D.LinearGradientBrush]::new($rect, $c1, $c2, 25.0)
$g.FillRectangle($brush, $rect)
# PS 5.1 cannot nest [T]::new([U]::M()), so build the colour first
$greenColor = [System.Drawing.Color]::FromArgb(255, 0, 200, 0)
$green = [System.Drawing.SolidBrush]::new($greenColor)
$g.FillRectangle($green, 0, 0, 120, 120)
$g.FillRectangle($green, ($w - 120), 0, 120, 120)
$g.FillRectangle($green, 0, ($h - 120), 120, 120)
$g.FillRectangle($green, ($w - 120), ($h - 120), 120, 120)
$white = [System.Drawing.Brushes]::White
$bold = [System.Drawing.FontStyle]::Bold
$font = [System.Drawing.Font]::new('Arial', 64, $bold)
$fmt = [System.Drawing.StringFormat]::new()
$fmt.Alignment = [System.Drawing.StringAlignment]::Center
$fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
$textRect = [System.Drawing.RectangleF]::new(0, 0, $w, $h)
$g.DrawString('PHOTO', $font, $white, $textRect, $fmt)
$g.Dispose()
$png = Join-Path $out 'photo.png'
$bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "wrote $png"

# ------------------------------------------------------------------ anim.gif
# A hand-rolled GIF was rejected by the JDK GIF parser ("Unexpected block type
# 0"), so generate it with ImageIO itself via tools\MakeGif.java, which is
# guaranteed to emit a standards-clean animated GIF.
$javac = Get-ChildItem -Path (Join-Path (Split-Path -Parent $root) 'FloatingImage\.toolchain\jdk') -Recurse -Filter 'javac.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $javac) { $javac = Get-Command javac.exe -ErrorAction SilentlyContinue }
if (-not $javac) { throw 'javac not found; run tools\build.ps1 once to bootstrap the JDK' }
$javacPath = if ($javac.FullName) { $javac.FullName } else { $javac.Source }
$javaPath = Join-Path (Split-Path -Parent $javacPath) 'java.exe'

$classDir = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classDir | Out-Null
& $javacPath -d $classDir (Join-Path $PSScriptRoot 'MakeGif.java')
if ($LASTEXITCODE -ne 0) { throw 'failed to compile MakeGif.java' }

$gifPath = Join-Path $out 'anim.gif'
& $javaPath -cp $classDir MakeGif $gifPath
if ($LASTEXITCODE -ne 0) { throw 'failed to generate anim.gif' }

# validate with GDI+: frame count + per-frame delays
$img = [System.Drawing.Image]::FromFile($gifPath)
$dim = [System.Drawing.Imaging.FrameDimension]::new($img.FrameDimensionsList[0])
$count = $img.GetFrameCount($dim)
$delayProp = $img.GetPropertyItem(0x5100)
$delays = @()
for ($i = 0; $i -lt $delayProp.Value.Length; $i += 4) {
    $delays += [BitConverter]::ToInt32($delayProp.Value, $i)
}
$img.Dispose()
Write-Host "  GDI+ frame count = $count, delays = $($delays -join ',')"
if ($count -lt 2) { throw "generated gif is not animated (frames=$count)" }
Write-Host "ASSETS OK" -ForegroundColor Green
