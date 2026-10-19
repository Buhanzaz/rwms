import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ComponentPropsWithoutRef,
} from "react"
import { flushSync } from "react-dom"
import { Moon, Sun } from "lucide-react"

import { useTheme } from "@/components/theme-provider"
import { cn } from "@/lib/utils"

type ViewTransitionHandle = {
  ready: Promise<void>
  finished: Promise<void>
}

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => void) => ViewTransitionHandle
}

type AnimatedThemeTogglerProps = ComponentPropsWithoutRef<"button"> & {
  duration?: number
}

function getCircleClipPaths(
  x: number,
  y: number,
  maxRadius: number,
  viewportWidth: number,
  viewportHeight: number
): [string, string] {
  const point = `${(x / viewportWidth) * 100}% ${(y / viewportHeight) * 100}%`
  const radius =
    (maxRadius /
      (Math.hypot(viewportWidth, viewportHeight) / Math.SQRT2)) *
    100

  return [`circle(0% at ${point})`, `circle(${radius}% at ${point})`]
}

export function AnimatedThemeToggler({
  className,
  duration = 400,
  ...props
}: AnimatedThemeTogglerProps) {
  const { setTheme } = useTheme()
  const [isDark, setIsDark] = useState(() =>
    document.documentElement.classList.contains("dark")
  )
  const buttonRef = useRef<HTMLButtonElement>(null)
  const isTransitioningRef = useRef(false)
  const activeAnimationRef = useRef<Animation | null>(null)

  const cancelAnimation = useCallback(() => {
    activeAnimationRef.current?.cancel()
    activeAnimationRef.current = null
  }, [])

  useEffect(() => {
    const root = document.documentElement
    const updateIcon = () => setIsDark(root.classList.contains("dark"))
    const observer = new MutationObserver(updateIcon)

    updateIcon()
    observer.observe(root, { attributes: true, attributeFilter: ["class"] })

    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    return () => {
      cancelAnimation()
      const root = document.documentElement
      if (root.dataset.magicuiThemeVt !== "active") return
      delete root.dataset.magicuiThemeVt
      root.style.removeProperty("--magicui-theme-toggle-vt-duration")
      root.style.removeProperty("--magicui-theme-vt-clip-from")
    }
  }, [cancelAnimation])

  const toggleTheme = useCallback(() => {
    const button = buttonRef.current
    const root = document.documentElement
    if (
      !button ||
      isTransitioningRef.current ||
      root.dataset.magicuiThemeVt === "active"
    ) {
      return
    }

    const applyTheme = () => {
      const nextTheme = root.classList.contains("dark") ? "light" : "dark"
      root.classList.remove("light", "dark")
      root.classList.add(nextTheme)
      setIsDark(nextTheme === "dark")
      setTheme(nextTheme)
    }

    const transitionDocument = document as ViewTransitionDocument
    if (
      typeof transitionDocument.startViewTransition !== "function" ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ) {
      applyTheme()
      return
    }

    const viewportWidth = window.innerWidth
    const viewportHeight = window.innerHeight
    const { top, left, width, height } = button.getBoundingClientRect()
    const x = left + width / 2
    const y = top + height / 2
    const maxRadius = Math.hypot(
      Math.max(x, viewportWidth - x),
      Math.max(y, viewportHeight - y)
    )
    const clipPath = getCircleClipPaths(
      x,
      y,
      maxRadius,
      viewportWidth,
      viewportHeight
    )

    root.dataset.magicuiThemeVt = "active"
    root.style.setProperty(
      "--magicui-theme-toggle-vt-duration",
      `${duration}ms`
    )
    root.style.setProperty("--magicui-theme-vt-clip-from", clipPath[0])

    const cleanup = () => {
      isTransitioningRef.current = false
      delete root.dataset.magicuiThemeVt
      root.style.removeProperty("--magicui-theme-toggle-vt-duration")
      root.style.removeProperty("--magicui-theme-vt-clip-from")
      cancelAnimation()
    }

    isTransitioningRef.current = true
    const transition = transitionDocument.startViewTransition(() => {
      flushSync(applyTheme)
    })

    transition.finished.finally(cleanup).catch(() => undefined)
    transition.ready
      .then(() => {
        activeAnimationRef.current = root.animate(
          { clipPath },
          {
            duration,
            easing: "ease-in-out",
            fill: "forwards",
            pseudoElement: "::view-transition-new(root)",
          } as KeyframeAnimationOptions
        )
      })
      .catch(() => undefined)
  }, [cancelAnimation, duration, setTheme])

  const label = isDark ? "Включить светлую тему" : "Включить тёмную тему"

  return (
    <button
      {...props}
      ref={buttonRef}
      type="button"
      aria-label={label}
      aria-pressed={isDark}
      title={label}
      onClick={toggleTheme}
      className={cn(
        "inline-flex size-9 shrink-0 items-center justify-center rounded-full border-0 bg-transparent p-0 text-foreground shadow-none outline-none transition-colors hover:bg-accent focus-visible:bg-accent",
        className
      )}
    >
      {isDark ? (
        <Moon aria-hidden="true" className="size-5" />
      ) : (
        <Sun aria-hidden="true" className="size-5" />
      )}
    </button>
  )
}
