Image to G-Code converter v1.4 for laser engraver or similar tools.  
Converts binary (black and white) images into 2D G-Code. White color treated as background color, any other color - as engraving/cutting color.  
author: Stas Makutin, stas@makutin.net, 2016  
  
Usage: <input file> [<output file>] [options]  
  
Options:  
-l, /l, --lineScan  
&nbsp;&nbsp;Generate image line-by-line instead of detecting continious regions.  
-v, /v, --vertical  
&nbsp;&nbsp;Trace vertical lines instead of horizontal.  
-s, /s, --speed, -cr, /cr, --cutRate  <feed rate>  
&nbsp;&nbsp;Engraving/cutting feed rate. Optional.  
-mr, /mr, --moveRate  <feed rate>  
&nbsp;&nbsp;Moving (not cutting) feed rate. Optional.  
-x, /x, --offsetX <offset>  
&nbsp;&nbsp;Target X-axis offset, in millimeters. Optional, default is 0.  
-y, /y, --offsetY <offset>  
&nbsp;&nbsp;Target Y-axis offset, in millimeters. Optional, default is 0.  
-w, /w, --width <width>  
&nbsp;&nbsp;Target width, in millimeters. If not provided then it will be calculated from provided height and input image width and height. If height is not provided then default width 100 will be used.  
-h, /h, --height <height>  
&nbsp;&nbsp;Target height, in millimeters. If not provided then it will be calculated from target width and input image width and height.
