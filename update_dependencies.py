#!/usr/bin/env python3
"""
Dependency updater script for Android Gradle Version Catalogs (libs.versions.toml).
Queries Google Maven, Maven Central, and the Gradle Plugin Portal to find the latest stable/unstable versions.
"""

import argparse
import os
import re
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed

def version_sort_key(v_str):
    """
    Returns a tuple suitable for comparing/sorting version strings semantically.
    Handles semantic versioning along with pre-release suffixes (alpha, beta, rc).
    """
    match = re.match(r'^(\d+(?:\.\d+)*)(.*)$', v_str)
    if not match:
        return ((), -1, ())
    numbers = tuple(int(x) for x in match.group(1).split('.'))
    suffix = match.group(2).strip('-._')
    
    # Classify suffix to weight release stages
    if not suffix:
        weight = 3  # Stable release
    elif any(x in suffix.lower() for x in ['rc', 'cr']):
        weight = 2  # Release candidate
    elif any(x in suffix.lower() for x in ['beta', 'b']):
        weight = 1  # Beta
    elif any(x in suffix.lower() for x in ['alpha', 'a', 'dev', 'milestone', 'm']):
        weight = 0  # Alpha / Dev / Milestone
    else:
        weight = 2.5  # Other suffixes
        
    suffix_parts = []
    if suffix:
        # Split suffix into string parts and numeric parts for proper comparison (e.g., 'alpha02' > 'alpha01')
        for part in re.split(r'(\d+)', suffix):
            if part.isdigit():
                suffix_parts.append((0, int(part)))
            elif part:
                suffix_parts.append((1, part.lower()))
                
    return (numbers, weight, tuple(suffix_parts))

def get_channel(v_str):
    """
    Classifies a version string into a release channel.
    """
    match = re.match(r'^(\d+(?:\.\d+)*)(.*)$', v_str)
    if not match:
        return 'stable'
    suffix = match.group(2).strip('-._').lower()
    if not suffix:
        return 'stable'
    if any(x in suffix for x in ['rc', 'cr']):
        return 'rc'
    if any(x in suffix for x in ['beta', 'b']):
        return 'beta'
    if any(x in suffix for x in ['alpha', 'a']):
        return 'alpha'
    if any(x in suffix for x in ['dev', 'milestone', 'm']):
        return 'dev'
    return 'other'

def get_best_version(versions, current_version, allow_pre_releases_for_stable=False):
    """
    Selects the best/latest version from a list of candidates.
    Respects version channels: e.g. if current is alpha, prefers newer alphas, and
    only falls back/upgrades to beta/rc/stable if no newer alpha exists.
    """
    current_key = version_sort_key(current_version)
    current_channel = get_channel(current_version)
    
    # Filter candidates that are strictly greater than current
    newer_candidates = []
    for v in versions:
        key = version_sort_key(v)
        if key > current_key:
            newer_candidates.append((key, v))
            
    if not newer_candidates:
        return current_version
        
    # Group newer candidates by channel
    channel_groups = {
        'stable': [],
        'rc': [],
        'beta': [],
        'alpha': [],
        'dev': [],
        'other': []
    }
    for key, v in newer_candidates:
        ch = get_channel(v)
        channel_groups[ch].append((key, v))
        
    # Standard channel hierarchy from current to stable
    hierarchy = ['dev', 'alpha', 'beta', 'rc', 'stable']
    
    if current_channel not in hierarchy:
        # Fallback to absolute sorting if current is not in standard hierarchy
        newer_candidates.sort()
        return newer_candidates[-1][1]
        
    # Prefer the current channel if any candidates exist in it
    if channel_groups[current_channel]:
        channel_groups[current_channel].sort()
        return channel_groups[current_channel][-1][1]
        
    # If no candidates in the current channel, fall back/upwards to more stable channels
    current_idx = hierarchy.index(current_channel)
    for ch in hierarchy[current_idx + 1:]:
        if channel_groups[ch]:
            channel_groups[ch].sort()
            return channel_groups[ch][-1][1]
            
    # If we are stable and allow pre-releases, we might have pre-release candidates
    if current_channel == 'stable' and allow_pre_releases_for_stable:
        newer_candidates.sort()
        return newer_candidates[-1][1]
        
    return current_version

def fetch_versions_from_url(url):
    """
    Fetches and parses maven-metadata.xml from a given repository URL.
    """
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=5) as response:
            content = response.read()
            root = ET.fromstring(content)
            return [v.text for v in root.findall(".//versioning/versions/version") if v.text]
    except Exception:
        return []

def get_library_urls(group, name):
    """
    Returns repository metadata URLs for standard Maven libraries.
    """
    group_path = group.replace('.', '/')
    return [
        f"https://maven.google.com/{group_path}/{name}/maven-metadata.xml",
        f"https://repo1.maven.org/maven2/{group_path}/{name}/maven-metadata.xml"
    ]

def get_plugin_urls(plugin_id):
    """
    Returns repository metadata URLs for Gradle plugins.
    """
    id_path = plugin_id.replace('.', '/')
    return [
        f"https://plugins.gradle.org/m2/{id_path}/{plugin_id}.gradle.plugin/maven-metadata.xml",
        f"https://maven.google.com/{id_path}/{plugin_id}.gradle.plugin/maven-metadata.xml",
        f"https://repo1.maven.org/maven2/{id_path}/{plugin_id}.gradle.plugin/maven-metadata.xml"
    ]

def check_artifact_version(item, allow_pre_releases_for_stable):
    """
    Queries repositories and checks for version upgrades.
    """
    item_type, key, current, is_plugin, details = item
    urls = get_plugin_urls(details) if is_plugin else get_library_urls(details[0], details[1])
    
    all_versions = []
    for url in urls:
        versions = fetch_versions_from_url(url)
        if versions:
            all_versions.extend(versions)
            
    if not all_versions:
        return item_type, key, current, current, "Not Found"
        
    all_versions = list(set(all_versions))
    best = get_best_version(all_versions, current, allow_pre_releases_for_stable)
    return item_type, key, current, best, "Success"

def parse_toml_sections(content):
    """
    Parses a TOML string into its top-level sections and variables.
    """
    versions = {}
    libraries = []
    plugins = []
    
    current_section = None
    for line in content.splitlines():
        line_clean = line.strip()
        if not line_clean or line_clean.startswith('#'):
            continue
        if line_clean.startswith('[') and line_clean.endswith(']'):
            current_section = line_clean[1:-1].strip()
            continue
            
        if current_section == "versions":
            m = re.match(r'^([a-zA-Z0-9_\-]+)\s*=\s*"([^"]+)"', line_clean)
            if m:
                versions[m.group(1)] = m.group(2)
        elif current_section == "libraries":
            m = re.match(r'^([a-zA-Z0-9_\-]+)\s*=\s*\{([^}]+)\}', line_clean)
            if m:
                lib_name = m.group(1)
                props_str = m.group(2)
                props = {}
                for part in props_str.split(','):
                    if '=' in part:
                        k, v = part.split('=', 1)
                        props[k.strip()] = v.strip().strip('"\'')
                libraries.append((lib_name, props))
        elif current_section == "plugins":
            m = re.match(r'^([a-zA-Z0-9_\-]+)\s*=\s*\{([^}]+)\}', line_clean)
            if m:
                plugin_name = m.group(1)
                props_str = m.group(2)
                props = {}
                for part in props_str.split(','):
                    if '=' in part:
                        k, v = part.split('=', 1)
                        props[k.strip()] = v.strip().strip('"\'')
                plugins.append((plugin_name, props))
                
    return versions, libraries, plugins

def main():
    parser = argparse.ArgumentParser(description="Update Android libs.versions.toml dependencies to latest versions.")
    parser.add_argument("--file", default="gradle/libs.versions.toml", help="Path to libs.versions.toml file.")
    parser.add_argument("--dry-run", action="store_true", help="Print updates without writing changes.")
    parser.add_argument("--max-workers", type=int, default=10, help="Max thread pool workers for concurrent requests.")
    parser.add_argument("--pre-releases", action="store_true", help="Allow upgrading stable versions to pre-releases (alpha/beta/rc).")
    
    args = parser.parse_args()
    
    if not os.path.exists(args.file):
        print(f"Error: file not found at '{args.file}'")
        return
        
    with open(args.file, 'r') as f:
        content = f.read()
        
    versions, libraries, plugins = parse_toml_sections(content)
    
    to_check = []
    version_refs_checked = set()
    
    # Libraries using version.ref
    for lib_name, props in libraries:
        v_ref = props.get('version.ref')
        if v_ref and v_ref in versions and v_ref not in version_refs_checked:
            group = props.get('group')
            name = props.get('name')
            if group and name:
                to_check.append(("catalog", v_ref, versions[v_ref], False, (group, name)))
                version_refs_checked.add(v_ref)
            elif 'module' in props:
                parts = props['module'].split(':')
                if len(parts) == 2:
                    to_check.append(("catalog", v_ref, versions[v_ref], False, (parts[0], parts[1])))
                    version_refs_checked.add(v_ref)
                    
    # Plugins using version.ref
    for plugin_name, props in plugins:
        v_ref = props.get('version.ref')
        if v_ref and v_ref in versions and v_ref not in version_refs_checked:
            p_id = props.get('id')
            if p_id:
                to_check.append(("catalog", v_ref, versions[v_ref], True, p_id))
                version_refs_checked.add(v_ref)
                
    # Libraries using inline version
    for lib_name, props in libraries:
        v_val = props.get('version')
        if v_val and not props.get('version.ref'):
            group = props.get('group')
            name = props.get('name')
            if group and name:
                to_check.append(("inline_lib", lib_name, v_val, False, (group, name)))
            elif 'module' in props:
                parts = props['module'].split(':')
                if len(parts) == 2:
                    to_check.append(("inline_lib", lib_name, v_val, False, (parts[0], parts[1])))
                    
    # Plugins using inline version
    for plugin_name, props in plugins:
        v_val = props.get('version')
        if v_val and not props.get('version.ref'):
            p_id = props.get('id')
            if p_id:
                to_check.append(("inline_plugin", plugin_name, v_val, True, p_id))

    print(f"Checking {len(to_check)} dependency versions...")
    
    results = []
    with ThreadPoolExecutor(max_workers=args.max_workers) as executor:
        futures = {executor.submit(check_artifact_version, item, args.pre_releases): item for item in to_check}
        for future in as_completed(futures):
            results.append(future.result())
            
    catalog_updates = {}
    inline_lib_updates = {}
    inline_plugin_updates = {}
    
    for item_type, key, current, best, status in results:
        if status == "Success" and current != best:
            if item_type == "catalog":
                catalog_updates[key] = (current, best)
            elif item_type == "inline_lib":
                inline_lib_updates[key] = (current, best)
            elif item_type == "inline_plugin":
                inline_plugin_updates[key] = (current, best)
                
    if not catalog_updates and not inline_lib_updates and not inline_plugin_updates:
        print("All dependencies are up to date.")
        return
        
    print("\nProposed Updates:")
    for key, (current, best) in sorted(catalog_updates.items()):
        print(f"  [Catalog Version] {key}: {current} -> {best}")
    for key, (current, best) in sorted(inline_lib_updates.items()):
        print(f"  [Inline Library] {key}: {current} -> {best}")
    for key, (current, best) in sorted(inline_plugin_updates.items()):
        print(f"  [Inline Plugin] {key}: {current} -> {best}")
        
    if args.dry_run:
        print("\nDry run completed. No files modified.")
        return
        
    lines = content.splitlines()
    updated_content_lines = []
    current_section = None
    
    for line in lines:
        line_strip = line.strip()
        if line_strip.startswith('[') and line_strip.endswith(']'):
            current_section = line_strip[1:-1].strip()
            updated_content_lines.append(line)
            continue
            
        if current_section == "versions":
            for v_name, (curr, best) in catalog_updates.items():
                pattern = rf'^(\s*{v_name}\s*=\s*")([^"]+)(")'
                if re.match(pattern, line):
                    line = re.sub(pattern, rf'\g<1>{best}\g<3>', line)
                    break
        elif current_section == "libraries":
            for lib_name, (curr, best) in inline_lib_updates.items():
                pattern = rf'^(\s*{lib_name}\s*=\s*\{{.*version\s*=\s*")([^"]+)(")'
                if re.match(pattern, line):
                    line = re.sub(pattern, rf'\g<1>{best}\g<3>', line)
                    break
        elif current_section == "plugins":
            for plugin_name, (curr, best) in inline_plugin_updates.items():
                pattern = rf'^(\s*{plugin_name}\s*=\s*\{{.*version\s*=\s*")([^"]+)(")'
                if re.match(pattern, line):
                    line = re.sub(pattern, rf'\g<1>{best}\g<3>', line)
                    break
                    
        updated_content_lines.append(line)
        
    updated_content = '\n'.join(updated_content_lines) + '\n'
    
    with open(args.file, 'w') as f:
        f.write(updated_content)
        
    print(f"\nSuccessfully updated {args.file}")

if __name__ == "__main__":
    main()
