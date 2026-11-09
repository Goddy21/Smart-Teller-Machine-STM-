# Run ON THE STM. Lists its monitors with their position, so you know where the upper screen is.
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Screen]::AllScreens | ForEach-Object {
  "{0,-14} primary={1,-5} x={2,-6} y={3,-6} {4}x{5}" -f $_.DeviceName.TrimStart('\','.'), $_.Primary, $_.Bounds.X, $_.Bounds.Y, $_.Bounds.Width, $_.Bounds.Height
}
"Use the x and y of the UPPER screen with open-teller-display-on-stm.cmd"
