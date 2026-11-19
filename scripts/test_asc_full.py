#!/usr/bin/env python3
"""Check actual Android pack capabilities and run the Java-generated software options."""
import pathlib, subprocess, tempfile, zipfile, re
ROOT=pathlib.Path(__file__).resolve().parents[1]
def run(*args):
    return subprocess.check_output(args,stderr=subprocess.STDOUT,text=True)
with tempfile.TemporaryDirectory() as td:
    tmp=pathlib.Path(td)
    with zipfile.ZipFile(ROOT/'app/libs/ffmpeg-kit-full-8.1.9.aar') as z:
        names=z.namelist()
        codec=next(x for x in names if x.startswith('jni/arm64-v8a/') and x.endswith('/libavcodec.so'))
        data=z.read(codec)
        for name in (b'hevc_mediacodec',b'h264_mediacodec',b'libvpx-vp9',b'libaom-av1',b'mpeg4'):
            assert name+b'\0' in data, ('missing Android encoder name',name)
        print('Android ARM64 Full pack: expected encoder identifiers present')
    java=ROOT/'app/src/main/java/com/xarkon/medialab/AscVideoOptions.java'
    run('javac','-d',str(tmp),str(java),str(ROOT/'scripts/TestAscVideoOptions.java'))
    main='com.xarkon.medialab.TestAscVideoOptions'
    print(run('java','-cp',str(tmp),main).strip())
    for codec in ('libvpx-vp9','libaom-av1','mpeg4'):
        options=run('java','-cp',str(tmp),main,codec).splitlines()
        # MPEG4 quantizer 4 is supported; this is a smoke test, not a quality gate.
        out=tmp/(codec+'.mkv')
        run('ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=160x96:rate=24','-t','1','-c:v',codec,*options,'-threads','2',str(out))
        assert out.stat().st_size>0
        run('ffmpeg','-v','error','-xerror','-i',str(out),'-f','null','-')
        print(codec+': Java options encode/decode PASS')

    for codec in ('libvpx-vp9','libaom-av1'):
        options=run('java','-cp',str(tmp),main,codec,'ultra').splitlines()
        out=tmp/('ultra-'+codec+'.mkv')
        run('ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=160x96:rate=24','-t','1','-c:v',codec,*options,'-threads','2',str(out))
        assert out.stat().st_size>0
        run('ffmpeg','-v','error','-xerror','-i',str(out),'-f','null','-')
        print(codec+': ULTRA options encode/decode PASS')
