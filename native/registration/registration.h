#pragma once
#include "../stars/stars.h"
namespace sari {
struct Transform { double a=1,b=0,tx=0,ty=0; void apply(double x,double y,double&ox,double&oy)const{ox=a*x-b*y+tx;oy=b*x+a*y+ty;} };
struct RegParams { int maxStars=30; double tol=2.0; int minInliers=6; double scaleTol=0.03; double minSep=10.0; };
struct RegResult { Transform t; int inliers=0; double rms=0,confidence=0; };
Status registerStars(const std::vector<Star>&ref,const std::vector<Star>&tgt,const RegParams&p,RegResult&out);
// cfa >= 0: the source is a Bayer mosaic. Each output pixel is interpolated only from source pixels of
// the same CFA position (same x/y parity), so R, Gr, Gb and B are never mixed and per-channel black
// offsets survive. cfa < 0: plain bilinear (mono / ordinary images).
Status warpTile(PlaneSource&src,const Transform&t,float gain,float offset,int x0,int y0,int tw,int th,float*out,int cfa=-1);
}
