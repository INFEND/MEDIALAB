use std::{ffi::{CStr,c_char},fs,path::Path,time::Duration,panic::catch_unwind};
use oxipng::{Options,StripChunks};

// Refuse signed/animated PNG and preserve all non-IDAT chunks byte-for-byte.
// This deliberately restricts the optimization to lossless IDAT recompression.
fn chunks(b:&[u8])->Option<Vec<&[u8]>> {
    if b.get(..8)?!=b"\x89PNG\r\n\x1a\n" {return None}
    let mut pos=8; let mut result=Vec::new();
    while pos<b.len(){
        let n=u32::from_be_bytes(b.get(pos..pos+4)?.try_into().ok()?) as usize;
        let end=pos.checked_add(n)?.checked_add(12)?;
        let chunk=b.get(pos..end)?;
        let kind=chunk.get(4..8)?;
        if kind==b"acTL"||kind==b"caBX" {return None}
        if kind!=b"IDAT" {result.push(chunk);}
        pos=end;
        if kind==b"IEND" {return if pos==b.len(){Some(result)}else{None}}
    }
    None
}
#[no_mangle]
pub unsafe extern "C" fn medialab_oxi(input:*const c_char,output:*const c_char,preset:u8)->i32 {
    if input.is_null()||output.is_null(){return 0}
    catch_unwind(|| {
        let a=unsafe{CStr::from_ptr(input)}.to_str().ok()?;
        let b=unsafe{CStr::from_ptr(output)}.to_str().ok()?;
        let size=fs::metadata(a).ok()?.len();
        if size==0||size>128*1024*1024||Path::new(a)==Path::new(b){return None}
        let data=fs::read(a).ok()?;
        let original=chunks(&data)?;
        let mut opt=Options::from_preset(preset.min(6));
        opt.strip=StripChunks::None;opt.optimize_alpha=false;opt.scale_16=false;
        opt.bit_depth_reduction=false;opt.color_type_reduction=false;
        opt.palette_reduction=false;opt.grayscale_reduction=false;opt.interlace=None;
        opt.max_decompressed_size=Some(256*1024*1024);
        opt.timeout=Some(Duration::from_secs(120));
        let optimized=oxipng::optimize_from_memory(&data,&opt).ok()?;
        if optimized.len()>=data.len()||chunks(&optimized)?!=original{return None}
        fs::write(b,optimized).ok()?;
        Some(())
    }).ok().flatten().is_some() as i32
}
