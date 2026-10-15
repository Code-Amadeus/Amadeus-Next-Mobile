const DELETE_WIDTH = 84
let openRow: HTMLElement | null = null

function slide(row: HTMLElement, x: number) {
  row.style.transform = x ? `translateX(${x}px)` : ''
  const remove = row.previousElementSibling as HTMLElement
  remove.style.opacity = String(Math.min(1, -x / DELETE_WIDTH))
}

/** 收起已展开的那一行；列表重新打开时调用。 */
export function closeSwiped() {
  if (openRow) slide(openRow, 0)
  openRow = null
}

/**
 * 左滑露出「删除」的列表行：同一时间只有一行展开，点别处先收起。
 * onDelete 返回 true 时整行收拢移除，之后调用 onRemoved。
 */
export function swipeRow(content: HTMLElement, { onTap, onDelete, onRemoved }: {
  onTap: () => void
  onDelete: () => Promise<boolean>
  onRemoved?: () => void
}): HTMLElement {
  const wrap = document.createElement('div')
  wrap.className = 'swipe-row'
  const remove = document.createElement('button')
  remove.className = 'swipe-delete'
  remove.textContent = '删除'
  content.classList.add('swipe-content')
  wrap.append(remove, content)

  // 先看手势以横向还是纵向为主，纵向留给列表滚动
  let startX = 0, startY = 0, base = 0, x = 0
  let axis: 'x' | 'y' | null = null
  let swiped = false
  content.addEventListener('touchstart', (event) => {
    startX = event.touches[0].clientX
    startY = event.touches[0].clientY
    base = openRow === content ? -DELETE_WIDTH : 0
    axis = null
    swiped = false
    content.style.transition = 'none'
  }, { passive: true })
  content.addEventListener('touchmove', (event) => {
    const dx = event.touches[0].clientX - startX
    const dy = event.touches[0].clientY - startY
    if (!axis) {
      if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return
      axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'
      if (axis === 'x' && openRow && openRow !== content) closeSwiped()
    }
    if (axis !== 'x') return
    event.preventDefault()
    swiped = true
    x = Math.min(0, Math.max(-DELETE_WIDTH * 1.3, base + dx))
    slide(content, x)
  }, { passive: false })
  content.addEventListener('touchend', () => {
    content.style.transition = ''
    if (axis !== 'x') return
    const open = x < -DELETE_WIDTH / 2
    slide(content, open ? -DELETE_WIDTH : 0)
    if (open) openRow = content
    else if (openRow === content) openRow = null
  })
  content.addEventListener('click', (event) => {
    // 滑动松手后浏览器仍可能补发一次 click
    if (swiped) { swiped = false; return }
    if (openRow) { closeSwiped(); return }
    // 行里自带的按钮、输入框自己处理点击
    if ((event.target as HTMLElement).closest('button:not(.swipe-content), input, textarea, select')) return
    onTap()
  })
  remove.addEventListener('click', async () => {
    if (!(await onDelete())) return
    if (openRow === content) openRow = null
    wrap.style.maxHeight = `${wrap.offsetHeight}px`
    requestAnimationFrame(() => wrap.classList.add('removing'))
    wrap.addEventListener('transitionend', () => { wrap.remove(); onRemoved?.() }, { once: true })
  })
  return wrap
}
