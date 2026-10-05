#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>
#include "../core/image.h"
namespace sari {
struct PipelineFrameReport {
  int index=0; bool included=false; int stars=0; float fwhm=0,roundness=0,noise=0,snr=0,score=0; int inliers=0; double rms=0;
};
struct PipelineReport { int status=0,framesInput=0,framesIncluded=0,tilesDone=0,tilesTotal=0; std::vector<PipelineFrameReport> frames; };
Status stackProject(const std::vector<std::string>& lightPaths,
                   int W,int H,
                   const std::string& darkPath,const std::string& flatPath,const std::string& biasPath,
                   const std::string& outputPath,const std::string& progressPath,const std::string& reportPath,
                   int tile,int workers,int startTile,
                   const std::function<bool(int,int)>& cancelOrContinue,
                   PipelineReport& out);
Status renderPreview(const std::string& f32Path,int W,int H,int cfa,int mode,float stretch,int maxDim,
                     std::vector<uint8_t>& rgba,int& outW,int& outH);
}
