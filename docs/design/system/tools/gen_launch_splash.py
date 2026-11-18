"""Compile the Katana splash art to native Android vectors, without runtime work.

art/katana-mark.svg   the ink katana: the launcher icon's close-up in an ink ring, one path.
art/katana-glint.svg  a four-point glint, its paper-coloured halo, and its route along the blade's edge.
Both are drawn by Universe/projects/katana/logo/tools/splash.py from the icon's own drawing.

drawable/launch_mark.xml      the mark alone: Android 9-11, and the pose with animations off.
drawable-v31/launch_mark.xml  the mark plus one glint that runs down the blade's edge in 820 ms and is gone again.

The glint uses linear keyframes sampled from motion(). Every channel ends where it started, so an app that is
ready early, or animations turned off, always leaves the plain mark. Run --check to detect stale resources and
to validate the whole interpolated motion inside Android's 192 dp splash icon circle.
"""
from __future__ import annotations

import argparse
import bisect
import copy
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
ART = ROOT / 'docs/design/system/project/components/AppLaunchSplash/art'
RES = ROOT / 'app/src/main/res'
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
ET.register_namespace('android', ANDROID)
ET.register_namespace('aapt', AAPT)
DURATION = 820
INK, PAPER = '?attr/launchMarkColor', '?attr/launchPaperColor'
CENTRE, MASK = 144, 96
# The glint: hidden until GLINT_IN, runs the route and flashes until GLINT_OUT, then slips back unseen.
GLINT_IN, GLINT_OUT, GLINT_TURN = 120, 600, 50


def attrs(**values):
    return {f'{{{ANDROID}}}{key}': str(value) for key, value in values.items()}


def number(value):
    return f'{value:.6f}'.rstrip('0').rstrip('.') if abs(value) > .0000005 else '0'


def clamp(value, low=0, high=1):
    return max(low, min(high, value))


def ease(value):
    u = clamp(value)
    return u * u * (3 - 2 * u)


def mix(a, b, u):
    return a + (b - a) * u


def svg_parts():
    parts = {}
    for element in ET.parse(ART / 'katana-mark.svg').getroot().iter():
        if element.tag.endswith('path'):
            parts['mark'] = element
    for element in ET.parse(ART / 'katana-glint.svg').getroot().iter():
        if element.get('id'):
            parts[element.get('id')] = element
    return parts


def route():
    r = svg_parts()['route']
    return tuple(float(r.get(k)) for k in ('x1', 'y1', 'x2', 'y2'))


def motion(t, path=None):
    """The glint group's values at t ms."""
    x0, y0, x1, y1 = path or route()
    if t <= GLINT_IN:
        return dict(translateX=x0, translateY=y0, rotation=0., scaleX=0., scaleY=0.)
    if t < GLINT_OUT:
        u = (t - GLINT_IN) / (GLINT_OUT - GLINT_IN)
        along = ease(u)
        # quick to flare, slower to fade
        s = math.sin(math.pi * u ** .8) ** 1.4
        return dict(translateX=mix(x0, x1, along), translateY=mix(y0, y1, along),
                    rotation=GLINT_TURN * along, scaleX=s, scaleY=s)
    back = ease((t - GLINT_OUT) / (DURATION - GLINT_OUT))
    return dict(translateX=mix(x1, x0, back), translateY=mix(y1, y0, back),
                rotation=mix(GLINT_TURN, 0, back), scaleX=0., scaleY=0.)


def vector(glint):
    parts = svg_parts()
    root = ET.Element('vector', attrs(width='288dp', height='288dp', viewportWidth=288, viewportHeight=288))
    mark = parts['mark']
    values = dict(name='katana_mark', pathData=mark.get('d'), fillColor=INK)
    if mark.get('fill-rule') == 'evenodd':
        values['fillType'] = 'evenOdd'
    ET.SubElement(root, 'path', attrs(**values))
    if glint:
        group = ET.SubElement(root, 'group', attrs(name='glint', **{k: number(v) for k, v in motion(0).items()}))
        # The halo is the splash's own background: a thin gap where the glint crosses the blade's ink.
        ET.SubElement(group, 'path', attrs(name='glint_halo', pathData=parts['halo'].get('d'), fillColor=PAPER))
        ET.SubElement(group, 'path', attrs(name='glint_star', pathData=parts['star'].get('d'), fillColor=INK))
    return root


def control_points(data):
    """Bezier control hulls conservatively include every rendered curve pixel."""
    tokens = re.findall(r'[A-Za-z]|-?\d*\.?\d+(?:[eE][+-]?\d+)?', data)
    points, i, command, x, y, start = [], 0, '', 0., 0., (0., 0.)
    counts = dict(M=2, L=2, C=6, Q=4, H=1, V=1, Z=0)
    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]; i += 1
            if command.upper() == 'Z':
                x, y = start
                continue
        size = counts[command.upper()]
        values = list(map(float, tokens[i:i + size])); i += size
        relative, c = command.islower(), command.upper()
        if c in ('H', 'V'):
            if c == 'H': x = values[0] + (x if relative else 0)
            else: y = values[0] + (y if relative else 0)
            points.append((x, y))
        else:
            for j in range(0, size, 2):
                points.append((values[j] + (x if relative else 0), values[j + 1] + (y if relative else 0)))
            x, y = points[-1]
            if c == 'M': start = (x, y); command = 'l' if relative else 'L'
    return points


def xml_text(root, comment):
    ET.indent(root, space='    ')
    return '<?xml version="1.0" encoding="utf-8"?>\n<!-- ' + comment + ' -->\n' + ET.tostring(root, encoding='unicode') + '\n'


def keyframe_times():
    return sorted(set(range(0, DURATION + 1, 20)) | {GLINT_IN, GLINT_OUT, DURATION})


def generate(check=False):
    still = vector(glint=False)
    art = vector(glint=True)
    times = keyframe_times()
    frames = [motion(t) for t in times]
    avd = ET.Element('animated-vector')
    ET.SubElement(avd, f'{{{AAPT}}}attr', {'name': 'android:drawable'}).append(copy.deepcopy(art))
    target = ET.SubElement(avd, 'target', attrs(name='glint'))
    animation = ET.SubElement(target, f'{{{AAPT}}}attr', {'name': 'android:animation'})
    animator = ET.SubElement(animation, 'objectAnimator', attrs(duration=DURATION, interpolator='@android:interpolator/linear'))
    for key in frames[0]:
        holder = ET.SubElement(animator, 'propertyValuesHolder', attrs(propertyName=key, valueType='floatType'))
        for t, frame in zip(times, frames):
            ET.SubElement(holder, 'keyframe', attrs(fraction=number(t / DURATION), value=number(frame[key])))
    outputs = {
        RES / 'drawable/launch_mark.xml': xml_text(still, 'Katana splash mark. Generated by docs/design/system/tools/gen_launch_splash.py; '
                                                         'static fallback and reduced-motion pose. Do not edit by hand.'),
        RES / 'drawable-v31/launch_mark.xml': xml_text(avd, 'Katana splash: the mark and one 820 ms glint down the blade; no added splash hold. '
                                                            'Generated by docs/design/system/tools/gen_launch_splash.py. Do not edit by hand.'),
    }
    for path, text in outputs.items():
        if check:
            if not path.exists() or path.read_text(encoding='utf-8') != text:
                raise SystemExit(f'Stale generated resource: {path}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
    parts = svg_parts()
    mark_r = max(math.hypot(x - CENTRE, y - CENTRE) for x, y in control_points(parts['mark'].get('d')))
    glint_pts = control_points(parts['halo'].get('d')) + control_points(parts['star'].get('d'))
    # Validate interpolation between keyframes too, not just the sampled poses.
    glint_r = 0.
    for t in range(0, DURATION + 1, 5):
        i = min(bisect.bisect_right(times, t), len(times) - 1)
        a, b = max(0, i - 1), i
        u = (t - times[a]) / (times[b] - times[a]) if a != b else 0
        f = {key: mix(frames[a][key], frames[b][key], u) for key in frames[a]}
        r = math.radians(f['rotation'])
        for x, y in glint_pts:
            x, y = x * f['scaleX'], y * f['scaleY']
            px = f['translateX'] + math.cos(r) * x - math.sin(r) * y
            py = f['translateY'] + math.sin(r) * x + math.cos(r) * y
            glint_r = max(glint_r, math.hypot(px - CENTRE, py - CENTRE))
    if max(mark_r, glint_r) > MASK:
        raise SystemExit(f'Splash art escapes the 192 dp mask: mark {mark_r:.2f}, glint {glint_r:.2f} / {MASK} dp')
    if any(abs(frames[0][k] - frames[-1][k]) > 1e-6 for k in frames[0]):
        raise SystemExit('The glint does not return to its starting pose')
    print(f'{"Verified" if check else "Generated"} native splash: {DURATION} ms, {len(times)} keyframes, '
          f'mark radius {mark_r:.2f}/{MASK} dp, glint radius {glint_r:.2f}/{MASK} dp')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    generate(parser.parse_args().check)
