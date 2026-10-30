param([string]$Target)
$desktop = [Environment]::GetFolderPath('Desktop')
$s = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $desktop '우리집 무전기.lnk'))
$s.TargetPath = $Target
$s.WorkingDirectory = Split-Path $Target
$s.Description = '우리집 무전기'
$s.Save()
