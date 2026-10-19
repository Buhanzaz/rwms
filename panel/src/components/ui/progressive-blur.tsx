import { cn } from "@/lib/utils"

const BLUR_LEVELS = [0.5, 1, 2, 4] as const

/**
 * Magic UI's layered backdrop-mask technique adapted to horizontal photo edges.
 * Four small-radius layers keep the effect affordable in virtualized grids.
 * https://magicui.design/docs/components/progressive-blur
 */
export function ProgressiveBlur({
  position,
  className,
  visibility = "hover",
}: {
  position: "left" | "right"
  className?: string
  visibility?: "hover" | "always" | "mobile-visible"
}) {
  return (
    <span
      aria-hidden="true"
      data-slot="progressive-blur"
      data-position={position}
      data-edge={position}
      data-visibility={visibility}
      className={cn(
        "photo-edge-blur pointer-events-none absolute inset-y-0 w-1/5",
        position === "left" ? "left-0" : "right-0",
        className
      )}
    >
      {BLUR_LEVELS.map((radius, index) => {
        const start = (index / BLUR_LEVELS.length) * 100
        const end = ((index + 1) / BLUR_LEVELS.length) * 100
        const mask = `linear-gradient(to ${position}, transparent ${start}%, black ${end}%)`
        return (
          <span
            key={radius}
            className="absolute inset-0"
            style={{
              backdropFilter: `blur(${radius}px)`,
              WebkitBackdropFilter: `blur(${radius}px)`,
              maskImage: mask,
              WebkitMaskImage: mask,
            }}
          />
        )
      })}
    </span>
  )
}
