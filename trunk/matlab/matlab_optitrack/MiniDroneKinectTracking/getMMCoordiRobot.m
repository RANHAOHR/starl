function out = getMMCoordiRobot(coordinates)
global mm_per_pixel
xCenterMM = 0;
yCenterMM = 0;
xCenterPx = 320;
yCenterPx = 240;

x = coordinates(1,1);
y = coordinates(1,2);

out(1,1) = xCenterMM + (x - xCenterPx) * mm_per_pixel;
out(1,2) = yCenterMM + (y - yCenterPx) * mm_per_pixel;
