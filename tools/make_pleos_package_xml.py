# Writes package.xml for the manually extracted Pleos Connect system image,
# using the license text and package details from the official repository XML.
import re, sys, html
from xml.sax.saxutils import escape

repo_xml, out_path = sys.argv[1], sys.argv[2]
s = open(repo_xml, encoding="utf-8").read()
lic = re.search(r'<license id="pleos-connect-sdk-license" type="text">(.*?)</license>', s, re.S).group(1)
lic_text = html.unescape(lic)
ns = ('xmlns:ns2="http://schemas.android.com/repository/android/common/02" '
      'xmlns:ns3="http://schemas.android.com/repository/android/common/01" '
      'xmlns:ns4="http://schemas.android.com/repository/android/generic/01" '
      'xmlns:ns5="http://schemas.android.com/repository/android/generic/02" '
      'xmlns:ns6="http://schemas.android.com/sdk/android/repo/addon2/01" '
      'xmlns:ns7="http://schemas.android.com/sdk/android/repo/addon2/02" '
      'xmlns:ns8="http://schemas.android.com/sdk/android/repo/addon2/03" '
      'xmlns:ns9="http://schemas.android.com/sdk/android/repo/repository2/01" '
      'xmlns:ns10="http://schemas.android.com/sdk/android/repo/repository2/02" '
      'xmlns:ns11="http://schemas.android.com/sdk/android/repo/repository2/03" '
      'xmlns:ns12="http://schemas.android.com/sdk/android/repo/sys-img2/03" '
      'xmlns:ns13="http://schemas.android.com/sdk/android/repo/sys-img2/02" '
      'xmlns:ns14="http://schemas.android.com/sdk/android/repo/sys-img2/01"')
xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
       f'<ns2:repository {ns}>'
       f'<license id="pleos-connect-sdk-license" type="text">{escape(lic_text)}</license>'
       '<localPackage path="system-images;android-34;pleos_car_emulator;x86_64" obsolete="false">'
       '<type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns12:sysImgDetailsType">'
       '<api-level>34</api-level>'
       '<tag><id>android-automotive</id><display>Pleos Connect v2.1.0 APIs</display></tag>'
       '<vendor><id>pleos</id><display>Pleos Connect v2.1.0</display></vendor>'
       '<abi>x86_64</abi></type-details>'
       '<revision><major>2</major></revision>'
       '<display-name>Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image</display-name>'
       '<uses-license ref="pleos-connect-sdk-license"/>'
       '<dependencies><dependency path="emulator"><min-revision><major>24</major></min-revision></dependency></dependencies>'
       '</localPackage></ns2:repository>')
open(out_path, "w", encoding="utf-8").write(xml)
print("wrote", out_path, len(xml))
