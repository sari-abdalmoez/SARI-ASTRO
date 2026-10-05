#include "export.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <vector>
#include <cstring>

namespace sari {
namespace {

static uint32_t crc32Update(uint32_t crc, const uint8_t* p, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        crc ^= p[i];
        for (int k = 0; k < 8; ++k) crc = (crc >> 1) ^ (0xedb88320u & (-(int)(crc & 1)));
    }
    return crc;
}

static uint32_t crc32All(const char type[4], const std::vector<uint8_t>& data) {
    uint32_t crc = crc32Update(0xffffffffu, reinterpret_cast<const uint8_t*>(type), 4);
    if (!data.empty()) crc = crc32Update(crc, data.data(), data.size());
    return ~crc;
}

static uint32_t adler32Update(uint32_t adler, const uint8_t* p, size_t n) {
    uint32_t a = adler & 0xffffu;
    uint32_t b = (adler >> 16) & 0xffffu;
    for (size_t i = 0; i < n; ++i) {
        a += p[i]; if (a >= 65521u) a -= 65521u;
        b += a; if (b >= 65521u) b -= 65521u;
    }
    return (b << 16) | a;
}

static void be32(std::ostream& o, uint32_t v) {
    const uint8_t b[4] = {uint8_t(v >> 24), uint8_t(v >> 16), uint8_t(v >> 8), uint8_t(v)};
    o.write(reinterpret_cast<const char*>(b), 4);
}

static void writeChunk(std::ostream& o, const char type[4], const std::vector<uint8_t>& data) {
    be32(o, static_cast<uint32_t>(data.size()));
    o.write(type, 4);
    if (!data.empty()) o.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    be32(o, crc32All(type, data));
}

static int cfaColor(int cfa, int x, int y) {
    const int px = x & 1, py = y & 1;
    switch (cfa & 3) {
        case 0: return (py == 0 && px == 0) ? 0 : ((py == 1 && px == 1) ? 2 : 1); // RGGB
        case 1: return (py == 0 && px == 1) ? 0 : ((py == 1 && px == 0) ? 2 : 1); // GRBG
        case 2: return (py == 0 && px == 0) ? 1 : ((py == 1 && px == 1) ? 1 : ((py == 0) ? 2 : 0)); // GBRG
        case 3: return (py == 0 && px == 0) ? 2 : ((py == 1 && px == 1) ? 0 : 1); // BGGR
        default: return 1;
    }
}

static float valueAt(const std::vector<float>& buf, int stride, int originY, int h, int x, int y) {
    x = std::max(0, std::min(stride - 1, x));
    y = std::max(originY, std::min(originY + h - 1, y));
    return buf[static_cast<size_t>(y - originY) * stride + x];
}

static float filteredRaw(const std::vector<float>& buf, int W, int originY, int stripH,
                         int cfa, int x, int y, float sigma, float strength) {
    const float center = valueAt(buf, W, originY, stripH, x, y);
    if (!(strength > 0.f) || !(sigma > 1e-7f)) return center;
    const int wanted = cfaColor(cfa, x, y);
    double sum = 0.0, ws = 0.0;
    float localScale = 0.f;
    for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
        const int xx = x + dx, yy = y + dy;
        if (xx < 0 || xx >= W || yy < originY || yy >= originY + stripH) continue;
        if ((dx != 0 || dy != 0) && cfaColor(cfa, xx, yy) != wanted) continue;
        const float v = valueAt(buf, W, originY, stripH, xx, yy);
        if (!std::isfinite(v)) continue;
        localScale = std::max(localScale, std::fabs(v - center));
        const float spatial = std::exp(-0.5f * float(dx * dx + dy * dy) / 2.0f);
        const float range = std::exp(-0.5f * ((v - center) * (v - center)) /
                                      (sigma * sigma * std::max(0.25f, strength * strength)));
        const double w = spatial * range;
        sum += w * v; ws += w;
    }
    if (ws <= 0.0) return center;
    const float smooth = static_cast<float>(sum / ws);
    const float preserve = localScale / (localScale + 2.0f * sigma + 1e-6f);
    const float alpha = std::max(0.f, std::min(0.85f * strength, (1.f - preserve) * strength));
    return center * (1.f - alpha) + smooth * alpha;
}

static float stretchValue(float v,float lo,float hi,float amount){
    if(!(hi>lo))return 0.f;
    const float x=std::max(0.f,std::min(1.f,(v-lo)/(hi-lo)));
    const float a=std::max(.1f,amount);
    return std::asinh(a*x)/std::asinh(a);
}

static float demosaicChannel(const std::vector<float>& buf, int W, int originY, int stripH,
                             int cfa, int x, int y, int wanted) {
    if (cfaColor(cfa, x, y) == wanted) return valueAt(buf, W, originY, stripH, x, y);
    double sum = 0.0; int n = 0;
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
        if (dx == 0 && dy == 0) continue;
        const int xx=x+dx, yy=y+dy;
        if (xx < 0 || xx >= W || yy < originY || yy >= originY + stripH) continue;
        if (cfaColor(cfa, xx, yy) != wanted) continue;
        const float v=valueAt(buf,W,originY,stripH,xx,yy);
        if(std::isfinite(v)){sum+=v;++n;}
    }
    if(n) return static_cast<float>(sum/n);
    // Edge fallback: search a 5x5 Bayer neighborhood.
    for(int r=2;r<=3;++r){sum=0;n=0;for(int dy=-r;dy<=r;++dy)for(int dx=-r;dx<=r;++dx){int xx=x+dx,yy=y+dy;if(xx<0||xx>=W||yy<originY||yy>=originY+stripH)continue;if(cfaColor(cfa,xx,yy)!=wanted)continue;float v=valueAt(buf,W,originY,stripH,xx,yy);if(std::isfinite(v)){sum+=v;++n;}}if(n)return static_cast<float>(sum/n);}return valueAt(buf,W,originY,stripH,x,y);
}

static bool quantiles(F32FileSource& src, int W, int H, float& lo, float& hi, float& noise) {
    const int step = std::max(1, static_cast<int>(std::sqrt(std::max(1, (W * H) / 65536))));
    const int rh = std::min(256, H);
    std::vector<float> row(static_cast<size_t>(W) * rh);
    std::vector<float> vals; vals.reserve(65536);
    for (int y=0; y<H && vals.size()<65536; y+=rh) {
        const int hh=std::min(rh,H-y); if(src.readRegion(0,y,W,hh,row.data())!=Status::Ok)return false;
        for(int yy=0;yy<hh&&vals.size()<65536;yy+=step)for(int x=0;x<W&&vals.size()<65536;x+=step){float v=row[(size_t)yy*W+x];if(std::isfinite(v))vals.push_back(v);}
    }
    if(vals.empty())return false;
    auto nth=[&](size_t k){std::nth_element(vals.begin(),vals.begin()+k,vals.end());return vals[k];};
    lo=nth((size_t)(0.005*(vals.size()-1)));hi=nth((size_t)(0.995*(vals.size()-1)));if(!(hi>lo))hi=lo+1e-3f;
    const float med=nth(vals.size()/2);for(float&v:vals)v=std::fabs(v-med);noise=1.4826f*nth(vals.size()/2);if(!(noise>0))noise=std::max(1e-4f,(hi-lo)*0.002f);return true;
}

static void colorGains(F32FileSource& src,int W,int H,int cfa,float& gr,float& gg,float& gb){
    std::vector<float> r,g,b; r.reserve(4096);g.reserve(4096);b.reserve(4096);const int step=std::max(1,(int)std::sqrt(std::max(1,(W*H)/32768)));const int rh=std::min(128,H);std::vector<float> row((size_t)W*rh);
    for(int y=0;y<H&&(r.size()<4096||g.size()<4096||b.size()<4096);y+=rh){int hh=std::min(rh,H-y);if(src.readRegion(0,y,W,hh,row.data())!=Status::Ok)break;for(int yy=0;yy<hh;yy+=step)for(int x=0;x<W;x+=step){float v=row[(size_t)yy*W+x];if(v<0.02f||v>0.8f||!std::isfinite(v))continue;int c=cfaColor(cfa,x,y+yy);if(c==0&&r.size()<4096)r.push_back(v);else if(c==1&&g.size()<4096)g.push_back(v);else if(c==2&&b.size()<4096)b.push_back(v);}}
    auto med=[](std::vector<float>&v){if(v.empty())return 1.f;size_t k=v.size()/2;std::nth_element(v.begin(),v.begin()+k,v.end());return v[k];};float mr=med(r),mg=med(g),mb=med(b);gg=1.f;gr=mr>1e-6f?std::max(.7f,std::min(1.5f,mg/mr)):1.f;gb=mb>1e-6f?std::max(.7f,std::min(1.5f,mg/mb)):1.f;
}

static void writeStoredIdat(std::ostream& out,const std::vector<uint8_t>&data,bool first){
    std::vector<uint8_t> payload;payload.reserve(data.size()+7);if(first){payload.push_back(0x78);payload.push_back(0x01);}uint16_t len=(uint16_t)data.size(),nlen=(uint16_t)~len;payload.push_back(0x00);payload.push_back((uint8_t)len);payload.push_back((uint8_t)(len>>8));payload.push_back((uint8_t)nlen);payload.push_back((uint8_t)(nlen>>8));payload.insert(payload.end(),data.begin(),data.end());writeChunk(out,"IDAT",payload);
}


static void fitsCard(std::ostream&out,const std::string&key,const std::string&value){
    if(key=="END"){std::string s="END";s.append(77,' ');out.write(s.data(),80);return;}
    std::string s=key;s.resize(8,' ');s+="= ";s+=value;if(s.size()<80)s.append(80-s.size(),' ');else if(s.size()>80)s.resize(80);out.write(s.data(),80);
}
static void fitsComment(std::ostream&out,const std::string&text){
    std::string s="COMMENT ";s+=text;if(s.size()<80)s.append(80-s.size(),' ');else if(s.size()>80)s.resize(80);out.write(s.data(),80);
}
static void fitsPad(std::ostream&out,std::streamoff bytes,char fill){const std::streamoff rem=bytes%2880;if(rem){std::vector<char>z(static_cast<size_t>(2880-rem),fill);out.write(z.data(),z.size());}}
static std::string cfaName(int cfa){switch(cfa&3){case 0:return "RGGB";case 1:return "GRBG";case 2:return "GBRG";case 3:return "BGGR";default:return "UNKNOWN";}}
static void beFloat(std::ostream&out,float v){uint32_t u=0;std::memcpy(&u,&v,4);const uint8_t b[4]={uint8_t(u>>24),uint8_t(u>>16),uint8_t(u>>8),uint8_t(u)};out.write(reinterpret_cast<const char*>(b),4);}

} // namespace

Status exportFullPng(const std::string&f32Path,int W,int H,int cfa,int mode,float stretch,float denoiseStrength,const std::string&outputPath){
    if(f32Path.empty()||outputPath.empty()||W<=0||H<=0) return Status::InvalidArgument;
    F32FileSource src(f32Path.c_str(),W,H);
    if(!src.ok()) return Status::IoError;
    float lo=0,hi=1,noise=0;if(!quantiles(src,W,H,lo,hi,noise))return Status::IoError;float gainR=1,gainG=1,gainB=1;if(mode!=0)colorGains(src,W,H,cfa,gainR,gainG,gainB);
    std::ofstream out(outputPath,std::ios::binary|std::ios::trunc);if(!out)return Status::IoError;static const uint8_t sig[8]={137,80,78,71,13,10,26,10};out.write((const char*)sig,8);
    std::vector<uint8_t> ihdr(13);ihdr[0]=(uint8_t)(W>>24);ihdr[1]=(uint8_t)(W>>16);ihdr[2]=(uint8_t)(W>>8);ihdr[3]=(uint8_t)W;ihdr[4]=(uint8_t)(H>>24);ihdr[5]=(uint8_t)(H>>16);ihdr[6]=(uint8_t)(H>>8);ihdr[7]=(uint8_t)H;ihdr[8]=16;ihdr[9]=2;writeChunk(out,"IHDR",ihdr);
    const int radius=2,strip=64;std::vector<float>buf((size_t)W*(strip+radius*2)),dbuf(buf.size());std::vector<uint8_t>row((size_t)1+(size_t)W*6);uint32_t adler=1;bool first=true;
    for(int y0=0;y0<H;y0+=strip){int yStart=std::max(0,y0-radius),yEnd=std::min(H,y0+strip+radius),readH=yEnd-yStart;if(src.readRegion(0,yStart,W,readH,buf.data())!=Status::Ok)return Status::IoError;dbuf=buf;if(denoiseStrength>0.f){for(int yy=yStart;yy<yEnd;++yy)for(int x=0;x<W;++x)dbuf[(size_t)(yy-yStart)*W+x]=filteredRaw(buf,W,yStart,readH,cfa,x,yy,noise,denoiseStrength);}
        const int rows=std::min(strip,H-y0);for(int y=0;y<rows;++y){row[0]=0;int gy=y0+y;for(int x=0;x<W;++x){float rr,gg,bb;if(mode==0){float v=valueAt(dbuf,W,yStart,readH,x,gy);rr=gg=bb=v;}else{rr=demosaicChannel(dbuf,W,yStart,readH,cfa,x,gy,0)*gainR;gg=demosaicChannel(dbuf,W,yStart,readH,cfa,x,gy,1)*gainG;bb=demosaicChannel(dbuf,W,yStart,readH,cfa,x,gy,2)*gainB;}rr=stretchValue(rr,lo,hi,stretch);gg=stretchValue(gg,lo,hi,stretch);bb=stretchValue(bb,lo,hi,stretch);if(mode==2){rr=std::pow(std::max(0.f,rr),.94f);gg=std::pow(std::max(0.f,gg),.94f);bb=std::pow(std::max(0.f,bb),.94f);}uint16_t R=(uint16_t)std::lround(std::max(0.f,std::min(1.f,rr))*65535.f),G=(uint16_t)std::lround(std::max(0.f,std::min(1.f,gg))*65535.f),B=(uint16_t)std::lround(std::max(0.f,std::min(1.f,bb))*65535.f);size_t p=1+(size_t)x*6;row[p]=(uint8_t)(R>>8);row[p+1]=(uint8_t)R;row[p+2]=(uint8_t)(G>>8);row[p+3]=(uint8_t)G;row[p+4]=(uint8_t)(B>>8);row[p+5]=(uint8_t)B;}
            adler=adler32Update(adler,row.data(),row.size());size_t off=0;while(off<row.size()){size_t take=std::min<size_t>(65535,row.size()-off);std::vector<uint8_t>block(row.begin()+off,row.begin()+off+take);writeStoredIdat(out,block,first);first=false;off+=take;}
        }
    }
    std::vector<uint8_t>fin={0x01,0x00,0x00,0xff,0xff,(uint8_t)(adler>>24),(uint8_t)(adler>>16),(uint8_t)(adler>>8),(uint8_t)adler};writeChunk(out,"IDAT",fin);writeChunk(out,"IEND",{});return out.good()?Status::Ok:Status::IoError;
}


Status exportLinearFits(const std::string&f32Path,int W,int H,int cfa,const std::string&outputPath){
    if(f32Path.empty()||outputPath.empty()||W<=0||H<=0)return Status::InvalidArgument;
    F32FileSource src(f32Path.c_str(),W,H);if(!src.ok())return Status::IoError;
    std::ofstream out(outputPath,std::ios::binary|std::ios::trunc);if(!out)return Status::IoError;
    fitsCard(out,"SIMPLE","  T");fitsCard(out,"BITPIX","  -32");fitsCard(out,"NAXIS","  2");
    fitsCard(out,"NAXIS1",std::string(" ")+std::to_string(W));fitsCard(out,"NAXIS2",std::string(" ")+std::to_string(H));
    fitsCard(out,"BSCALE","  1.0");fitsCard(out,"BZERO","  0.0");fitsCard(out,"CFA","'"+cfaName(cfa)+"'");fitsCard(out,"ORIGIN","'SARI ASTRO'");
    fitsComment(out,"Linear stacked Bayer master; no demosaic or stretch applied.");fitsCard(out,"END","");fitsPad(out,out.tellp(),' ');
    std::vector<float>row(static_cast<size_t>(W));
    for(int y=0;y<H;++y){if(src.readRegion(0,y,W,1,row.data())!=Status::Ok)return Status::IoError;for(int x=0;x<W;++x)beFloat(out,row[x]);}
    fitsPad(out,out.tellp(),'\0');return out.good()?Status::Ok:Status::IoError;
}

} // namespace sari
