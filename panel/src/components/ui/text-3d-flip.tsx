import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  type ElementType,
  type ReactNode,
} from "react"
import {
  useAnimate,
  type AnimationOptions,
  type ValueAnimationTransition,
} from "motion/react"

import { cn } from "@/lib/utils"

const hasSegmenter = typeof Intl !== "undefined" && "Segmenter" in Intl

function splitIntoCharacters(text: string): string[] {
  if (hasSegmenter) {
    const segmenter = new Intl.Segmenter("en", { granularity: "grapheme" })
    return Array.from(segmenter.segment(text), ({ segment }) => segment)
  }

  return Array.from(text)
}

function extractTextFromChildren(children: ReactNode): string {
  if (children == null) return ""
  if (typeof children === "string") return children
  if (typeof children === "number") return String(children)

  if (Array.isArray(children)) {
    return children.map(extractTextFromChildren).join("")
  }

  return ""
}

const rotationMap = {
  top: "rotateX(90deg)",
  right: "rotateY(90deg)",
  bottom: "rotateX(-90deg)",
  left: "rotateY(-90deg)",
} as const

const defaultTransition: ValueAnimationTransition = {
  type: "spring",
  damping: 30,
  stiffness: 300,
}

type Text3DFlipProps = {
  children: ReactNode
  as?: ElementType
  className?: string
  textClassName?: string
  flipTextClassName?: string
  staggerDuration?: number
  staggerFrom?: "first" | "last" | "center" | number | "random"
  transition?: ValueAnimationTransition | AnimationOptions
  rotateDirection?: keyof typeof rotationMap
}

export function Text3DFlip({
  children,
  as: ElementTag = "span",
  className,
  textClassName,
  flipTextClassName,
  staggerDuration = 0.05,
  staggerFrom = "first",
  transition = defaultTransition,
  rotateDirection = "right",
}: Text3DFlipProps) {
  const isAnimatingRef = useRef(false)
  const isMountedRef = useRef(false)
  const [scope, animate] = useAnimate()
  const rotationTransform = rotationMap[rotateDirection]

  useEffect(() => {
    isMountedRef.current = true

    return () => {
      isMountedRef.current = false
      isAnimatingRef.current = false
    }
  }, [])

  const text = useMemo(() => extractTextFromChildren(children), [children])
  const characters = useMemo(() => splitIntoCharacters(text), [text])

  const getStaggerDelay = useCallback(
    (index: number, totalCharacters: number) => {
      if (staggerFrom === "first") return index * staggerDuration
      if (staggerFrom === "last") {
        return (totalCharacters - 1 - index) * staggerDuration
      }
      if (staggerFrom === "center") {
        const center = Math.floor(totalCharacters / 2)
        return Math.abs(center - index) * staggerDuration
      }
      if (staggerFrom === "random") {
        const randomIndex = Math.floor(Math.random() * totalCharacters)
        return Math.abs(randomIndex - index) * staggerDuration
      }

      return Math.abs(staggerFrom - index) * staggerDuration
    },
    [staggerDuration, staggerFrom]
  )

  const handleHoverStart = useCallback(async () => {
    if (isAnimatingRef.current) return
    isAnimatingRef.current = true

    try {
      const delays = characters.map((_, index) =>
        getStaggerDelay(index, characters.length)
      )

      await animate(
        ".text-3d-flip-char",
        { transform: rotationTransform },
        {
          ...transition,
          delay: (index: number) => delays[index],
        }
      )

      if (!isMountedRef.current) return

      await animate(
        ".text-3d-flip-char",
        { transform: "rotateX(0deg) rotateY(0deg)" },
        { duration: 0 }
      )
    } finally {
      if (isMountedRef.current) {
        isAnimatingRef.current = false
      }
    }
  }, [animate, characters, getStaggerDelay, rotationTransform, transition])

  return (
    <ElementTag
      className={cn("relative inline-flex", className)}
      onMouseEnter={handleHoverStart}
      ref={scope}
    >
      <span className="sr-only">{text}</span>
      {characters.map((character, index) => (
        <CharacterBox
          key={`${character}-${index}`}
          character={character}
          textClassName={textClassName}
          flipTextClassName={flipTextClassName}
          rotateDirection={rotateDirection}
        />
      ))}
    </ElementTag>
  )
}

const secondFaceTransforms = {
  top: "rotateX(-90deg) translateZ(0.5lh)",
  right:
    "rotateY(90deg) translateX(50%) rotateY(-90deg) translateX(-50%) rotateY(-90deg) translateX(50%)",
  bottom: "rotateX(90deg) translateZ(0.5lh)",
  left: "rotateY(90deg) translateX(50%) rotateY(-90deg) translateX(50%) rotateY(-90deg) translateX(50%)",
} as const

const frontFaceTransforms = {
  top: "translateZ(0.5lh)",
  bottom: "translateZ(0.5lh)",
  left: "rotateY(90deg) translateX(50%) rotateY(-90deg)",
  right: "rotateY(-90deg) translateX(50%) rotateY(90deg)",
} as const

const containerTransforms = {
  top: "translateZ(-0.5lh)",
  bottom: "translateZ(-0.5lh)",
  left: "rotateY(90deg) translateX(50%) rotateY(-90deg)",
  right: "rotateY(90deg) translateX(50%) rotateY(-90deg)",
} as const

const CharacterBox = memo(function CharacterBox({
  character,
  textClassName,
  flipTextClassName,
  rotateDirection,
}: {
  character: string
  textClassName?: string
  flipTextClassName?: string
  rotateDirection: keyof typeof rotationMap
}) {
  return (
    <span
      className="text-3d-flip-char inline transform-3d"
      style={{ transform: containerTransforms[rotateDirection] }}
    >
      <span
        className={cn("relative h-[1lh] backface-hidden", textClassName)}
        style={{ transform: frontFaceTransforms[rotateDirection] }}
      >
        {character}
      </span>
      <span
        className={cn(
          "absolute top-0 left-0 h-[1lh] backface-hidden",
          flipTextClassName
        )}
        style={{ transform: secondFaceTransforms[rotateDirection] }}
      >
        {character}
      </span>
    </span>
  )
})
