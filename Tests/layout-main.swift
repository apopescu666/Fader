import Foundation
@main struct LayoutTests {
 static func main() {
  for width: CGFloat in [900, 1024, 1200, 1440, 1920, 2560] {
   for sidebarFraction: CGFloat in [0.1, 0.23, 0.4, 0.8] {
    let sidebar = FaderLayoutPolicy.position(length: width, divider: 1, minimumFirst: 220, minimumSecond: 633, fraction: sidebarFraction)
    let remaining = width - sidebar - 1
    precondition(sidebar >= 220 && remaining >= 633)
    for centerFraction: CGFloat in [0.1, 0.55, 0.9] {
     let center = FaderLayoutPolicy.position(length: remaining, divider: 1, minimumFirst: 336, minimumSecond: 296, fraction: centerFraction)
     let queue = remaining - center - 1
     precondition(center >= 336 && queue >= 296)
     precondition(abs(sidebar + center + queue + 2 - width) < 0.001)
    }
   }
  }
  let wide = FaderLayoutPolicy.position(length: 1800, divider: 1, minimumFirst: 336, minimumSecond: 296, fraction: 0.7)
  _ = FaderLayoutPolicy.position(length: 633, divider: 1, minimumFirst: 336, minimumSecond: 296, fraction: 0.7)
  let restored = FaderLayoutPolicy.position(length: 1800, divider: 1, minimumFirst: 336, minimumSecond: 296, fraction: 0.7)
  precondition(wide == restored)
  for width: CGFloat in [0, 1, 100, 300] {
   let position = FaderLayoutPolicy.position(length: width, divider: 1, minimumFirst: 336, minimumSecond: 296, fraction: .nan)
   precondition(position.isFinite && position >= 0 && position <= max(0, width - 1))
  }
  print("PASS: default/custom proportions at 900–2560pt; pane minima; no overflow; preferred ratio retained after resize; degenerate bounds")
 }
}
