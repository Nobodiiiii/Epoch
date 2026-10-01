"""Validate every Windows-relevant LWJGL GLFW function-table symbol, without UI."""
import argparse,ctypes,json,pathlib,re,subprocess,zipfile
here=pathlib.Path(__file__).resolve().parent
root=here.parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--dll',type=pathlib.Path,default=here/'epoch-glfw.dll')
parser.add_argument('--output',type=pathlib.Path,default=here/'export-verification.json')
args=parser.parse_args()
dll=ctypes.CDLL(str(args.dll.resolve()))
javap=r'C:\Program Files\Java\jdk-17.0.15.6-hotspot\bin\javap.exe'
results={}
for version in ('3.2.2','3.3.1'):
    jar=root/'.minecraft/libraries/org/lwjgl/lwjgl-glfw'/version/('lwjgl-glfw-'+version+'.jar')
    classes=['org.lwjgl.glfw.'+c+'$Functions' for c in ('GLFW','GLFWNativeWin32','GLFWNativeWGL','GLFWNativeEGL','GLFWNativeOSMesa','GLFWVulkan')]
    required=set()
    for cls in classes:
        if cls.replace('.','/')+'.class' not in zipfile.ZipFile(jar).namelist():continue
        output=subprocess.check_output([javap,'-p','-c','-classpath',str(jar),cls],text=True,stderr=subprocess.DEVNULL)
        required.update(re.findall(r'// String (glfw\w+)',output))
    missing=[symbol for symbol in sorted(required) if not hasattr(dll,symbol)]
    results[version]={'checked':len(required),'missing':missing,'symbols':sorted(required)}
    if missing:raise SystemExit('Missing '+version+': '+str(missing))
dll.glfwGetVersionString.restype=ctypes.c_char_p
report={'nativeVersion':dll.glfwGetVersionString().decode(),'dll':str(args.dll.resolve()),'bindings':results}
args.output.write_text(json.dumps(report,indent=2))
print(report['nativeVersion'])
for version,result in results.items():print(version, result['checked'],'exports verified')
