#!/usr/bin/env python3
"""Regression: copied audio must not shift the central ASC PRO sample."""
import json, pathlib, subprocess, tempfile
def run(*a):
    return subprocess.check_output(a, stderr=subprocess.STDOUT, text=True)
with tempfile.TemporaryDirectory() as tmp:
    root=pathlib.Path(tmp); src=root/'source.mp4'; out=root/'sample.mkv'
    run('ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=30000/1001','-f','lavfi','-i','sine=frequency=400:sample_rate=48000','-t','12','-c:v','libx264','-preset','ultrafast','-g','240','-c:a','aac',str(src))
    run('ffmpeg','-v','error','-y','-ss','3.5','-i',str(src),'-ss','0','-t','5','-fps_mode:v:0','passthrough','-map','0:v:0','-map','0:a?','-c','copy','-c:v:0','libx264','-crf','10','-preset','superfast',str(out))
    p=json.loads(run('ffprobe','-v','error','-show_entries','stream=codec_type,start_time:format=duration','-of','json',str(out)))
    duration=float(p['format']['duration'])
    assert abs(duration-5)<=0.1,p
    video=next(x for x in p['streams'] if x['codec_type']=='video')
    assert abs(float(video['start_time']))<=0.05,p
    assert any(x['codec_type']=='audio' for x in p['streams']),p
    java=pathlib.Path('app/src/main/java/com/xarkon/medialab/SmartCompression.java').read_text()
    assert 'if(video&&length>0)Collections.addAll(a,"-ss","0");' in java
    print(f'ASC PRO copied-audio trim: PASS ({duration:.3f}s)')
