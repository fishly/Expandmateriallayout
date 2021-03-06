package cn.enjoytoday.expandrefresh

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.support.v4.view.MotionEventCompat
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.StaggeredGridLayoutManager
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.Transformation
import android.widget.AbsListView
import android.widget.RelativeLayout
import android.widget.ScrollView

@Suppress("DEPRECATED_IDENTITY_EQUALS")
/**
 * @date 17-10-18.
 * @className ExpandRefreshLayout
 * @serial 1.0.0
 *
 * 用于控制添加上拉刷新和下拉刷新的
 */
class ExpandRefreshLayout(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : ViewGroup(context, attrs, defStyleAttr) {

    private val HEADER_VIEW_HEIGHT = 50// HeaderView height (dp)

    private val DECELERATE_INTERPOLATION_FACTOR = 2f
    private val INVALID_POINTER = -1
    private val DRAG_RATE = .5f

    private val SCALE_DOWN_DURATION = 150
    private val ANIMATE_TO_TRIGGER_DURATION = 200
    private val ANIMATE_TO_START_DURATION = 200
    private val DEFAULT_CIRCLE_TARGET = 64

    //内的目标View,比如RecyclerView,ListView,ScrollView,GridView
    private var mTarget:View? = null

    private var mListener:OnPullRefreshListener? = null// 下拉刷新listener
    private var mOnPushLoadMoreListener:OnPushLoadMoreListener? = null// 上拉加载更多

    private var mRefreshing = false
    private var mLoadMore = false
    private var mTouchSlop:Int=0
    private var mTotalDragDistance = -1f
    private var mMediumAnimationDuration:Int=0
    private var mCurrentTargetOffsetTop:Int = 0
    private var mOriginalOffsetCalculated = false

    private var mInitialMotionY:Float = 0.toFloat()
    private var mIsBeingDragged:Boolean = false
    private var mActivePointerId = INVALID_POINTER
    private val mScale:Boolean = false

    private var mReturningToStart:Boolean = false
    private var mDecelerateInterpolator: DecelerateInterpolator?=null
    private val LAYOUT_ATTRS = intArrayOf(android.R.attr.enabled)

    private var mHeadViewContainer:HeadViewContainer? = null
    private var mFooterViewContainer: RelativeLayout? = null
    private var mHeaderViewIndex = -1
    private var mFooterViewIndex = -1

     var mFrom:Int = 0

    private var mStartingScale:Float = 0.toFloat()

     var mOriginalOffsetTop:Int = 0

    private var mScaleAnimation: Animation? = null

    private var mScaleDownAnimation:Animation? = null

    private var mScaleDownToStartAnimation:Animation? = null

    // 最后停顿时的偏移量px，与DEFAULT_CIRCLE_TARGET正比
    private var mSpinnerFinalOffset:Float=0f

    private var mNotify:Boolean = false

    private var mHeaderViewWidth:Int=0// headerView的宽度

    private var mFooterViewWidth:Int=0

    private var mHeaderViewHeight:Int=0

    private var mFooterViewHeight:Int=0

    private var mUsingCustomStart:Boolean = false

    var isTargetScrollWithLayout = true

    private var pushDistance = 0

    private var defaultProgressView:CircleProgressView? = null

    private var usingDefaultHeader = true

    private var density = 1.0f

    private var isProgressEnable = true


    /**
     * 下拉时，超过距离之后，弹回来的动画监听器
     */
    private val mRefreshListener = object: Animation.AnimationListener {
         override fun onAnimationStart(animation:Animation?) {
            isProgressEnable = false
        }


        override fun onAnimationRepeat(animation:Animation?) {}

        override fun onAnimationEnd(animation:Animation?) {
            log(message = "onAnimationEnd")
            isProgressEnable = true
            if (mRefreshing) {
                if (mNotify) {
                    if (usingDefaultHeader) {
                        ViewCompat.setAlpha(defaultProgressView, 1.0f)
                        defaultProgressView!!.setOnDraw(true)
                        Thread(defaultProgressView).start()
                    }
                    if (mListener != null) {
                        mListener!!.onRefresh()
                    }
                }
            } else {
                mHeadViewContainer!!.visibility = View.GONE
                if (mScale){
                    setAnimationProgress(0f)
                }else {
                    setTargetOffsetTopAndBottom(mOriginalOffsetTop - mCurrentTargetOffsetTop, true)
                }
            }
            mCurrentTargetOffsetTop = mHeadViewContainer!!.top
            updateListenerCallBack()
        }





    }



    /**
     * 更新回调
     */
    private fun updateListenerCallBack() {
        val distance = mCurrentTargetOffsetTop + mHeadViewContainer!!.getHeight()
        if (mListener != null) mListener!!.onPullDistance(distance)
        if (usingDefaultHeader && isProgressEnable) defaultProgressView!!.setPullDistance(distance)

    }


    /**
     * 添加头布局
     *
     * @param child
     */
     fun setHeaderView(child:View?) {
        if (child == null || mHeadViewContainer == null) return
        usingDefaultHeader = false
        mHeadViewContainer!!.removeAllViews()
        val layoutParams = RelativeLayout.LayoutParams(mHeaderViewWidth, mHeaderViewHeight)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        mHeadViewContainer!!.addView(child, layoutParams)

    }

     fun setFooterView(child:View?) {
         if (child == null || mFooterViewContainer == null) return
         mFooterViewContainer!!.removeAllViews()
         val layoutParams = RelativeLayout.LayoutParams(mFooterViewWidth, mFooterViewHeight)
         mFooterViewContainer!!.addView(child, layoutParams)

}


    constructor(context: Context):this(context,null,0)
    constructor(context: Context,attrs: AttributeSet):this(context,attrs,0)

     init{
         //Distance in pixels a touch can wander before we think the user is scrolling,当手的移动超过这个距离才开始移动控件
         mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop

         //中等长度的动画的事件
         mMediumAnimationDuration = resources.getInteger(android.R.integer.config_mediumAnimTime)

         //用于设置重写onDraw方法(默认情况下为了性能考虑,会忽略onDraw)
         setWillNotDraw(false)

          //动画插值器
          //AccelerateDecelerateInterpolator 在动画开始与结束的地方速率改变比较慢，在中间的时候加速
          //AccelerateInterpolator  在动画开始的地方速率改变比较慢，然后开始加速
          //AnticipateInterpolator 开始的时候向后然后向前甩
          //AnticipateOvershootInterpolator 开始的时候向后然后向前甩一定值后返回最后的值
          //BounceInterpolator   动画结束的时候弹起
          //CycleInterpolator 动画循环播放特定的次数，速率改变沿着正弦曲线
          //ecelerateInterpolator 在动画开始的地方快然后慢
          //LinearInterpolator   以常量速率改变
          //OvershootInterpolator    向前甩一定值后再回到原来位置
          mDecelerateInterpolator = DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR)

         //
         val a = context.obtainStyledAttributes(attrs, LAYOUT_ATTRS)
         isEnabled = a.getBoolean(0, true)
         a.recycle()

         val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
         val display = wm.defaultDisplay
         val metrics = resources.displayMetrics
         mHeaderViewWidth = display.width
         mFooterViewWidth = display.width
         mHeaderViewHeight = (HEADER_VIEW_HEIGHT * metrics.density).toInt()
         mFooterViewHeight = (HEADER_VIEW_HEIGHT * metrics.density).toInt()
         defaultProgressView = CircleProgressView(getContext())
         createHeaderViewContainer()
         createFooterViewContainer()
         ViewCompat.setChildrenDrawingOrderEnabled(this, true)
         mSpinnerFinalOffset = DEFAULT_CIRCLE_TARGET * metrics.density
         density = metrics.density
         mTotalDragDistance = mSpinnerFinalOffset


     }


    /**
     * 孩子节点绘制的顺序
     *
     * @param childCount
     * @param i
     * @return
     */
     override fun getChildDrawingOrder(childCount:Int, i:Int):Int {// 将新添加的View,放到最后绘制
        if (mHeaderViewIndex < 0 && mFooterViewIndex < 0) return i
        else if (i == childCount - 2) return mHeaderViewIndex
        else if (i == childCount - 1) return mFooterViewIndex

        val bigIndex = if (mFooterViewIndex > mHeaderViewIndex) mFooterViewIndex else mHeaderViewIndex
        val smallIndex = if (mFooterViewIndex < mHeaderViewIndex) mFooterViewIndex else mHeaderViewIndex
        if (i >= smallIndex && i < bigIndex - 1) return i + 1
        else if (i >= bigIndex || (i == bigIndex - 1)) return i + 2


        return i

    }


    /**
     * 创建头布局的容器
     */
    private fun createHeaderViewContainer() {
        val layoutParams = RelativeLayout.LayoutParams((mHeaderViewHeight * 0.8).toInt(), (mHeaderViewHeight * 0.8).toInt())
        layoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        mHeadViewContainer = HeadViewContainer(context)
        mHeadViewContainer!!.visibility = View.GONE
        defaultProgressView!!.visibility = View.VISIBLE
        defaultProgressView!!.setOnDraw(false)
        mHeadViewContainer!!.addView(defaultProgressView, layoutParams)
        addView(mHeadViewContainer)
    }



    /**
     * 添加底部布局
     */
    private fun createFooterViewContainer() {
        mFooterViewContainer = RelativeLayout(context)
        mFooterViewContainer!!.visibility = View.GONE
        addView(mFooterViewContainer)

    }


    /**
     * 设置
     *
     * @param listener
     */
    fun setOnPullRefreshListener(listener:OnPullRefreshListener):ExpandRefreshLayout{
        mListener = listener
        return  this
    }


    fun setHeaderViewBackgroundColor(color:Int) {
        mHeadViewContainer!!.setBackgroundColor(color)
    }


     fun setFooterViewBackgroundColor(color:Int) {
         mFooterViewContainer!!.setBackgroundColor(color)
     }



    /**
     * 设置上拉加载更多的接口
     *
     * @param onPushLoadMoreListener
     */
    fun setOnPushLoadMoreListener(onPushLoadMoreListener:OnPushLoadMoreListener) {
        this.mOnPushLoadMoreListener = onPushLoadMoreListener
    }


    /**
     * Notify the widget that refresh state has changed. Do not call this when
     * refresh is triggered by a swipe gesture.
     *
     * @param refreshing Whether or not the view should show refresh progress.
     */
    fun setRefreshing(refreshing:Boolean) {
        log(message = "setRefreshing:$refreshing")
        if (refreshing && mRefreshing != refreshing) {
            // scale and show
            log(message = "setRefreshing  scale and show")
            mRefreshing = refreshing
            var endTarget = 0
            endTarget = if (!mUsingCustomStart) {
                (mSpinnerFinalOffset + mOriginalOffsetTop).toInt()
            } else {
                mSpinnerFinalOffset.toInt()
            }
            setTargetOffsetTopAndBottom(endTarget - mCurrentTargetOffsetTop, true /* requires update */)
            mNotify = false
            startScaleUpAnimation(mRefreshListener)
        } else {
            setRefreshing(refreshing, false /* notify */)
            if (usingDefaultHeader) {
                defaultProgressView!!.setOnDraw(false)
            }
        }

    }

    private fun startScaleUpAnimation(listener: Animation.AnimationListener?) {
        mHeadViewContainer!!.visibility = View.VISIBLE
        mScaleAnimation = object:Animation() {
            public override fun applyTransformation(interpolatedTime:Float, t: Transformation) {
                setAnimationProgress(interpolatedTime)
            }
        }
        mScaleAnimation!!.duration = mMediumAnimationDuration.toLong()
        if (listener != null) {
            mHeadViewContainer!!.setAnimationListener(listener)
        }
        mHeadViewContainer!!.clearAnimation()
        mHeadViewContainer!!.startAnimation(mScaleAnimation)
    }

    private fun setAnimationProgress(progress:Float) {
        var progress = progress
        if (!usingDefaultHeader) {
            progress = 1f
        }
        ViewCompat.setScaleX(mHeadViewContainer, progress)
        ViewCompat.setScaleY(mHeadViewContainer, progress)

    }

    private fun setRefreshing(refreshing:Boolean, notify:Boolean) {

        log(message = "setRefreshing  refreshing:$refreshing,and notify:$notify")
        if (mRefreshing != refreshing) {
            mNotify = notify
            ensureTarget()
            mRefreshing = refreshing
            if (mRefreshing) {
                animateOffsetToCorrectPosition(mCurrentTargetOffsetTop, mRefreshListener)
            } else {
//                startScaleDownAnimation(mRefreshListener)
                animateOffsetToStartPosition(mCurrentTargetOffsetTop, mRefreshListener)
            }
        }
    }

    private fun startScaleDownAnimation(listener: Animation.AnimationListener?) {
        mScaleDownAnimation = object:Animation() {
            public override fun applyTransformation(interpolatedTime:Float, t:Transformation) {
                setAnimationProgress(1 - interpolatedTime)
            }
        }
        mScaleDownAnimation!!.duration = SCALE_DOWN_DURATION.toLong()
        mHeadViewContainer!!.setAnimationListener(listener)
        mHeadViewContainer!!.clearAnimation()
        mHeadViewContainer!!.startAnimation(mScaleDownAnimation)
    }

     fun isRefreshing():Boolean = mRefreshing




    /**
     * 确保mTarget不为空<br></br>
     * mTarget一般是可滑动的ScrollView,ListView,RecyclerView等
     */
    private fun ensureTarget() {
        if (mTarget == null) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child != mHeadViewContainer && child != mFooterViewContainer) {
                    mTarget = child
                    break
                }
            }
        }
    }





    /**
     * Set the distance to trigger a sync in dips
     *
     * @param distance
     */
    fun setDistanceToTriggerSync(distance:Int) {
        mTotalDragDistance = distance.toFloat()

    }


    /**
     *
     */
    override fun onLayout(changed:Boolean, left:Int, top:Int, right:Int, bottom:Int) {
         val width = measuredWidth
         val height = measuredHeight
         if (childCount == 0) {
             return
         }
         if (mTarget == null) {
             ensureTarget()
         }
         if (mTarget == null) {
             return
         }
         var distance = mCurrentTargetOffsetTop + mHeadViewContainer!!.measuredHeight
         if (!isTargetScrollWithLayout) {
             // 判断标志位，如果目标View不跟随手指的滑动而滑动，将下拉偏移量设置为0
             distance = 0
         }
         val child = mTarget
         val childLeft = paddingLeft
         val childTop = paddingTop + distance - pushDistance// 根据偏移量distance更新
         val childWidth = width - paddingLeft - paddingRight
         val childHeight = height - paddingTop - paddingBottom
         child!!.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)// 更新目标View的位置

         val headViewWidth = mHeadViewContainer!!.measuredWidth
         val headViewHeight = mHeadViewContainer!!.measuredHeight
         mHeadViewContainer!!.layout((width / 2 - headViewWidth / 2),
                 mCurrentTargetOffsetTop, (width / 2 + headViewWidth / 2),
                 mCurrentTargetOffsetTop + headViewHeight)// 更新头布局的位置

         val footViewWidth = mFooterViewContainer!!.measuredWidth
         val footViewHeight = mFooterViewContainer!!.measuredHeight

         mFooterViewContainer!!.layout((width / 2 - footViewWidth / 2),
                 height - pushDistance, (width / 2 + footViewWidth / 2),
                 height + footViewHeight - pushDistance)



     }

    public override fun onMeasure(widthMeasureSpec:Int, heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (mTarget == null) {
            ensureTarget()
        }
        if (mTarget == null) {
            return
        }
        mTarget!!.measure(View.MeasureSpec.makeMeasureSpec(measuredWidth
                - paddingLeft - paddingRight, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(measuredHeight - paddingTop - paddingBottom, View.MeasureSpec.EXACTLY))

        mHeadViewContainer!!.measure(View.MeasureSpec.makeMeasureSpec(mHeaderViewWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(3 * mHeaderViewHeight, View.MeasureSpec.EXACTLY))


        mFooterViewContainer!!.measure(View.MeasureSpec.makeMeasureSpec(
                mFooterViewWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec
                .makeMeasureSpec(mFooterViewHeight, View.MeasureSpec.EXACTLY))


        if (!mUsingCustomStart && !mOriginalOffsetCalculated) {
            mOriginalOffsetCalculated = true
            mOriginalOffsetTop = -mHeadViewContainer!!.measuredHeight
            mCurrentTargetOffsetTop = mOriginalOffsetTop
            updateListenerCallBack()
        }
        mHeaderViewIndex = (0 until childCount).firstOrNull { getChildAt(it) === mHeadViewContainer } ?: -1
        mFooterViewIndex = (0 until childCount).firstOrNull { getChildAt(it) === mFooterViewContainer } ?: -1
    }



    /**
     * 判断目标View是否滑动到顶部-还能否继续滑动
     *
     * @return
     */
    private fun isChildScrollToTop():Boolean = !ViewCompat.canScrollVertically(mTarget, -1)



    /**
     * 是否滑动到底部
     *
     * @return
     */
    private fun isChildScrollToBottom():Boolean {
        if (mTarget is RecyclerView) {
            val recyclerView = mTarget as RecyclerView?
            val layoutManager = recyclerView!!.layoutManager
            val count = recyclerView.adapter.itemCount
            if (layoutManager is LinearLayoutManager && count > 0) {
                if (layoutManager.findLastCompletelyVisibleItemPosition() === count - 1) {
                    return true
                }
            } else if (layoutManager is StaggeredGridLayoutManager && count > 0) {
                val lastItems = IntArray(2)
                layoutManager.findLastCompletelyVisibleItemPositions(lastItems)
                val lastItem = Math.max(lastItems[0], lastItems[1])
                if (lastItem == count - 1) {
                    return true
                }
            }
            return false
        } else if (mTarget is AbsListView) {
            val absListView = mTarget as AbsListView?
            val count = absListView!!.adapter.count
            val fristPos = absListView.firstVisiblePosition
            var flag=false
            if (fristPos == 0 && absListView.getChildAt(0).top >= absListView.paddingTop) {
                flag= false
            }
            val lastPos = absListView.lastVisiblePosition
            if (lastPos > 0 && count > 0 && lastPos == count - 1) {
                flag= true
            }
            return flag
        } else if (mTarget is ScrollView) {
            val scrollView = mTarget as ScrollView?
            val view = scrollView!!.getChildAt(scrollView.childCount - 1)
            if (view != null) {
                val diff = (view.bottom - (scrollView.height + scrollView.scrollY))
                if (diff == 0) {
                    return true
                }
            }
        } else if (mTarget is NestedScrollView) {
            val nestedScrollView = mTarget as NestedScrollView?
            val view = nestedScrollView!!.getChildAt(nestedScrollView.childCount - 1) as View
                val diff = (view.bottom - (nestedScrollView.height + nestedScrollView.scrollY))
                if (diff == 0) {
                    return true
                }
        }
        return false


    }



    /**
     * 主要判断是否应该拦截子View的事件<br></br>
     * 如果拦截，则交给自己的OnTouchEvent处理<br></br>
     * 否者，交给子View处理<br></br>
     */
     override fun onInterceptTouchEvent(ev:MotionEvent):Boolean {

        ensureTarget()
        val action = MotionEventCompat.getActionMasked(ev)
        if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false
        }
        if (!isEnabled || mReturningToStart || mRefreshing || mLoadMore || (!isChildScrollToTop() && !isChildScrollToBottom()))
            return false

        // 下拉刷新判断
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                setTargetOffsetTopAndBottom(mOriginalOffsetTop - mHeadViewContainer!!.top, true)// 恢复HeaderView的初始位置
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0)
                mIsBeingDragged = false
                val initialMotionY = getMotionEventY(ev, mActivePointerId)
                if (initialMotionY == -1f) return false

                mInitialMotionY = initialMotionY// 记录按下的位置
                if (mActivePointerId == INVALID_POINTER) return false

                val y = getMotionEventY(ev, mActivePointerId)
                if (y == -1f) return false

                var yDiff = 0f







                if (isChildScrollToBottom()) {
                    yDiff = mInitialMotionY - y// 计算上拉距离
                    if (yDiff > mTouchSlop && !mIsBeingDragged) {// 判断是否上拉的距离足够
                        mIsBeingDragged = true// 正在上拉

                    }
                }
                if(isChildScrollToTop()) {
                    yDiff = y - mInitialMotionY// 计算下拉距离
                    if (yDiff > mTouchSlop && !mIsBeingDragged) {// 判断是否下拉的距离足够
                        mIsBeingDragged = true// 正在下拉
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mActivePointerId == INVALID_POINTER) return false

                val y = getMotionEventY(ev, mActivePointerId)
                if (y == -1f) return false

                var yDiff = 0f
                if (isChildScrollToBottom()) {
                    yDiff = mInitialMotionY - y
                    if (yDiff> mTouchSlop && !mIsBeingDragged) {
                        mIsBeingDragged = true
                    }
                }


                if(isChildScrollToTop())  {
                    yDiff = y - mInitialMotionY
                    if (yDiff > mTouchSlop && !mIsBeingDragged) {
                        mIsBeingDragged = true
                    }
                }
            }

            MotionEventCompat.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mIsBeingDragged = false
                mActivePointerId = INVALID_POINTER
            }
        }
//        println(message = "onInterceptTouchEvent mIsBeingDragged:$mIsBeingDragged")
        return mIsBeingDragged// 如果正在拖动，则拦截子View的事件

    }

    private fun getMotionEventY(ev:MotionEvent, activePointerId:Int):Float {
        val index = MotionEventCompat.findPointerIndex(ev,
                activePointerId)
        if (index < 0) return -1f
        return MotionEventCompat.getY(ev, index)

    }

     override fun requestDisallowInterceptTouchEvent(b:Boolean) {


     }


    var beforey=-1f


    override fun onTouchEvent(ev:MotionEvent):Boolean {

//        Log.e("onTouchEvent","拦截子view.")
        val action = MotionEventCompat.getActionMasked(ev)
        if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false
        }
        if (!isEnabled || mReturningToStart || (ViewCompat.canScrollVertically(mTarget, -1) && ViewCompat.canScrollVertically(mTarget, 1))) {
            // 如果子View可以滑动，不拦截事件，交给子View处理
            return false
        }



       return handlerTouchEvent(ev,action)
//        return if (isChildScrollToBottom() ) handlerPushTouchEvent(ev, action)  // 上拉加载更多
//        else handlerPullTouchEvent(ev, action)      // 下拉刷新


    }


    var scrollTop=false

    /**
     * 处理上下滑动事件
     */
    private fun handlerTouchEvent(ev:MotionEvent, action:Int):Boolean{
//        Log.e("handlerTouchEvent","处理上下滑动事件,handlerTouchEvent.")
        when(action){
            MotionEvent.ACTION_DOWN -> {
                mActivePointerId = ev.getPointerId(0)
                mIsBeingDragged = false
            }

            MotionEvent.ACTION_MOVE -> {
                /**
                 * action_move 滑动事件
                 */
                val pointerIndex = ev.findPointerIndex(mActivePointerId)
                if (pointerIndex < 0) {
                    Log.e("handlerTouchEvent","Got ACTION_MOVE event but have an invalid active pointer id.")
                    return false
                }

                val y = ev.getY(pointerIndex)
                var overScroll = (mInitialMotionY - y) * DRAG_RATE
                if (mIsBeingDragged) {

                    if (overScroll>0){
                        /**
                         * 上拉刷新
                         */
//                        Log.e("handlerTouchEvent","上拉 action_move:$overScroll.")
                        if (!scrollTop)
                            scrollTop=true
                        pushDistance = overScroll.toInt()
                        updateFooterViewPosition()
                        if (mOnPushLoadMoreListener != null) {
                            mOnPushLoadMoreListener!!.onPushEnable(pushDistance >= mFooterViewHeight)
                        }

                    }else{
                        /**
                         * 下拉刷新
                         */

//                        Log.e("handlerTouchEvent","下拉 action_move:$overScroll.")
                        overScroll *= (-1)
                        val originalDragPercent = overScroll / mTotalDragDistance
                        if (originalDragPercent < 0) return false

                        val dragPercent = Math.min(1f, Math.abs(originalDragPercent))
                        val extraOS = Math.abs(overScroll) - mTotalDragDistance
                        val slingshotDist = if (mUsingCustomStart)
                            mSpinnerFinalOffset - mOriginalOffsetTop
                        else
                            mSpinnerFinalOffset
                        val tensionSlingshotPercent = Math.max(0f, Math.min(extraOS, slingshotDist * 2)
                                / slingshotDist)
                        val tensionPercent = ((tensionSlingshotPercent / 4) - Math
                                .pow((tensionSlingshotPercent / 4).toDouble(), 2.0)).toFloat() * 2f
                        val extraMove = (slingshotDist) * tensionPercent * 2f

                        val targetY = mOriginalOffsetTop + ((slingshotDist * dragPercent) + extraMove).toInt()
                        if (mHeadViewContainer!!.visibility != View.VISIBLE) {
                            mHeadViewContainer!!.visibility = View.VISIBLE
                        }

                        if (!mScale) {
                            ViewCompat.setScaleX(mHeadViewContainer, 1f)
                            ViewCompat.setScaleY(mHeadViewContainer, 1f)
                        }



                        if (usingDefaultHeader) {
                            var alpha = overScroll / mTotalDragDistance
                            if (alpha >= 1.0f) {
                                alpha = 1.0f
                            }
                            ViewCompat.setScaleX(defaultProgressView, alpha)
                            ViewCompat.setScaleY(defaultProgressView, alpha)
                            ViewCompat.setAlpha(defaultProgressView, alpha)
                        }
                        if (overScroll < mTotalDragDistance) {
                            if (mScale) {
                                setAnimationProgress(overScroll / mTotalDragDistance)
                            }
                            if (mListener != null) {
                                mListener!!.onPullEnable(false)
                            }
                        } else {
                            if (mListener != null) mListener!!.onPullEnable(true)

                        }
                        setTargetOffsetTopAndBottom(targetY - mCurrentTargetOffsetTop, true)
                    }
                }
            }


            MotionEventCompat.ACTION_POINTER_DOWN -> {
                /**
                 * action_pointer_down
                 */
                val index = MotionEventCompat.getActionIndex(ev)
                mActivePointerId = ev.getPointerId(index)
            }

            MotionEventCompat.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->{
                /**
                 * action_up and action cancel handler
                 */

                val flag=scrollTop
                scrollTop=false
                if (!flag) {

//                    Log.e("handlerTouchEvent", "下拉 action_up or action cancel.")
                    /**
                     * 下拉
                     */
                    if (mActivePointerId == INVALID_POINTER) {
                        if (action == MotionEvent.ACTION_UP) {
                            log(message = "Got ACTION_UP event but don't have an active pointer id.")
                        }
                        return false
                    }
                    val pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId)
                    val y = MotionEventCompat.getY(ev, pointerIndex)
                    val overscrollTop = (y - mInitialMotionY) * DRAG_RATE
                    mIsBeingDragged = false
                    if (overscrollTop > mTotalDragDistance) {
                        setRefreshing(true, true /* notify */)
                    } else {
                        mRefreshing = false
                        var listener: Animation.AnimationListener? = null
                        if (!mScale) {
                            listener = object : Animation.AnimationListener {
                                override fun onAnimationStart(animation: Animation?) {

                                }

                                override fun onAnimationEnd(animation: Animation?) {
                                    if (!mScale) {
                                        startScaleDownAnimation(null)
                                    }
                                }

                                override fun onAnimationRepeat(animation: Animation) {}

                            }
                            animateOffsetToStartPosition(mCurrentTargetOffsetTop, listener)
                        }
                    }
                    mActivePointerId = INVALID_POINTER
                    return false
                }else {

//                    Log.e("handlerTouchEvent", "上拉 action_up or action cancel.")
                    /**
                     * 上拉刷新
                     */

                    if (mActivePointerId == INVALID_POINTER) {
                        if (action == MotionEvent.ACTION_UP) {
                            log(message = "Got ACTION_UP event but don't have an active pointer id.")
                        }
                        return false
                    }
                    val pointerIndex = ev.findPointerIndex(mActivePointerId)
                    val y = ev.getY(pointerIndex)
                    val overScrollBottom = (mInitialMotionY - y) * DRAG_RATE// 松手是下拉的距离
                    mIsBeingDragged = false
                    mActivePointerId = INVALID_POINTER
                    pushDistance = if (overScrollBottom < mFooterViewHeight || mOnPushLoadMoreListener == null) 0  // 直接取消
                    else mFooterViewHeight // 下拉到mFooterViewHeight

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                        updateFooterViewPosition()
                        if (pushDistance == mFooterViewHeight && mOnPushLoadMoreListener != null) {
                            mLoadMore = true
                            mOnPushLoadMoreListener!!.onLoadMore()
                        }
                    } else {
                        animatorFooterToBottom(overScrollBottom.toInt(), pushDistance)
                    }


                    return false
                }


            }

        }



        return false
    }





    private fun handlerPullTouchEvent(ev:MotionEvent, action:Int):Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mActivePointerId = ev.getPointerId(0)
                mIsBeingDragged = false
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = ev.findPointerIndex(mActivePointerId)
                if (pointerIndex < 0) {
                    log(message =  "Got ACTION_MOVE event but have an invalid active pointer id.")
                    return false
                }

                val y = ev.getY(pointerIndex)
                val overscrollTop = (y - mInitialMotionY) * DRAG_RATE
                if (mIsBeingDragged) {
                    val originalDragPercent = overscrollTop / mTotalDragDistance
                    if (originalDragPercent < 0) return false

                    val dragPercent = Math.min(1f, Math.abs(originalDragPercent))
                    val extraOS = Math.abs(overscrollTop) - mTotalDragDistance
                    val slingshotDist = if (mUsingCustomStart)
                        mSpinnerFinalOffset - mOriginalOffsetTop
                    else
                        mSpinnerFinalOffset
                    val tensionSlingshotPercent = Math.max(0f, Math.min(extraOS, slingshotDist * 2)
                            / slingshotDist)
                    val tensionPercent = ((tensionSlingshotPercent / 4) - Math
                            .pow((tensionSlingshotPercent / 4).toDouble(), 2.0)).toFloat() * 2f
                    val extraMove = (slingshotDist) * tensionPercent * 2f

                    val targetY = mOriginalOffsetTop + ((slingshotDist * dragPercent) + extraMove).toInt()
                    if (mHeadViewContainer!!.visibility != View.VISIBLE) {
                        mHeadViewContainer!!.visibility = View.VISIBLE
                    }

                    if (!mScale) {
                        ViewCompat.setScaleX(mHeadViewContainer, 1f)
                        ViewCompat.setScaleY(mHeadViewContainer, 1f)
                    }



                    if (usingDefaultHeader) {
                        var alpha = overscrollTop / mTotalDragDistance
                        if (alpha >= 1.0f) {
                            alpha = 1.0f
                        }
                        ViewCompat.setScaleX(defaultProgressView, alpha)
                        ViewCompat.setScaleY(defaultProgressView, alpha)
                        ViewCompat.setAlpha(defaultProgressView, alpha)
                    }
                    if (overscrollTop < mTotalDragDistance) {
                        if (mScale) {
                            setAnimationProgress(overscrollTop / mTotalDragDistance)
                        }

                        if (mListener != null) {
                            mListener!!.onPullEnable(false)
                        }
                    } else {
                        if (mListener != null) mListener!!.onPullEnable(true)

                    }
                    setTargetOffsetTopAndBottom(targetY - mCurrentTargetOffsetTop, true)
                }
            }
            MotionEventCompat.ACTION_POINTER_DOWN -> {
                val index = MotionEventCompat.getActionIndex(ev)
                mActivePointerId = MotionEventCompat.getPointerId(ev, index)
            }

            MotionEventCompat.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mActivePointerId == INVALID_POINTER) {
                    if (action == MotionEvent.ACTION_UP) {
                        log(message = "Got ACTION_UP event but don't have an active pointer id.")
                    }
                    return false
                }
                val pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId)
                val y = MotionEventCompat.getY(ev, pointerIndex)
                val overscrollTop = (y - mInitialMotionY) * DRAG_RATE
                mIsBeingDragged = false
                if (overscrollTop > mTotalDragDistance) {
                    setRefreshing(true, true /* notify */)
                } else {
                    mRefreshing = false
                    var listener: Animation.AnimationListener? = null
                    if (!mScale) {
                        listener = object : Animation.AnimationListener {
                            override fun onAnimationStart(animation: Animation?) {

                            }

                            override fun onAnimationEnd(animation: Animation?) {
                                if (!mScale) {
                                    startScaleDownAnimation(null)
                                }
                            }

                            override fun onAnimationRepeat(animation: Animation) {}

                        }
                        animateOffsetToStartPosition(mCurrentTargetOffsetTop, listener)
                    }
                }
                mActivePointerId = INVALID_POINTER
                return false
            }
        }

        return true

    }



    /**
     * 处理上拉加载更多的Touch事件
     *
     * @param ev
     * @param action
     * @return
     */
    private fun handlerPushTouchEvent(ev:MotionEvent, action:Int):Boolean {

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0)
                mIsBeingDragged = false
                log(message =  "debug:onTouchEvent ACTION_DOWN")
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = ev.findPointerIndex(mActivePointerId)
                if (pointerIndex < 0) {
                    log(message = "Got ACTION_MOVE event but have an invalid active pointer id.")
                    return false
                }
                val y = ev.getY(pointerIndex)
                val overScrollBottom = (mInitialMotionY - y) * DRAG_RATE
                if (mIsBeingDragged) {
                    pushDistance = overScrollBottom.toInt()
                    updateFooterViewPosition()
                    if (mOnPushLoadMoreListener != null) {
                        mOnPushLoadMoreListener!!.onPushEnable(pushDistance >= mFooterViewHeight)
                    }
                }
            }
            MotionEventCompat.ACTION_POINTER_DOWN -> {
                val index = MotionEventCompat.getActionIndex(ev)
                mActivePointerId = ev.getPointerId(index)
            }

            MotionEventCompat.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mActivePointerId == INVALID_POINTER) {
                    if (action == MotionEvent.ACTION_UP) {
                        log(message = "Got ACTION_UP event but don't have an active pointer id.")
                    }
                    return false
                }
                val pointerIndex = ev.findPointerIndex(mActivePointerId)
                val y = ev.getY(pointerIndex)
                val overScrollBottom = (mInitialMotionY - y) * DRAG_RATE// 松手是下拉的距离
                mIsBeingDragged = false
                mActivePointerId = INVALID_POINTER
                pushDistance = if (overScrollBottom < mFooterViewHeight || mOnPushLoadMoreListener == null) 0  // 直接取消
                else mFooterViewHeight // 下拉到mFooterViewHeight

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                    updateFooterViewPosition()
                    if (pushDistance == mFooterViewHeight && mOnPushLoadMoreListener != null) {
                        mLoadMore = true
                        mOnPushLoadMoreListener!!.onLoadMore()
                    }
                } else {
                    animatorFooterToBottom(overScrollBottom.toInt(), pushDistance)
                }
                return false
            }
        }
        return true
    }



    /**
     * 松手之后，使用动画将Footer从距离start变化到end
     *
     * @param start
     * @param end
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private fun animatorFooterToBottom(start:Int, end:Int) {
        val valueAnimator = ValueAnimator.ofInt(start, end)
        valueAnimator.duration = 150
        valueAnimator.addUpdateListener { _ ->
            // update
            pushDistance = valueAnimator.animatedValue as Int
            updateFooterViewPosition()
        }

        valueAnimator.addListener(object: AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                if (end > 0 && mOnPushLoadMoreListener != null) {
                    // start loading more
                    mLoadMore = true
                    mOnPushLoadMoreListener!!.onLoadMore()
                } else {
                    resetTargetLayout()
                    mLoadMore = false
                }
            }
        })
        valueAnimator.interpolator = mDecelerateInterpolator
        valueAnimator.start()

    }



    /**
     * 设置停止加载
     *
     * @param loadMore
     */
    fun setLoadMore(loadMore:Boolean) {
        if (!loadMore && mLoadMore) {// 停止加载
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                mLoadMore = false
                pushDistance = 0
                updateFooterViewPosition()
            } else {
                animatorFooterToBottom(mFooterViewHeight, 0)
            }
        }
    }

    private fun animateOffsetToCorrectPosition(from:Int, listener: Animation.AnimationListener?) {
        log(message = "animateOffsetToCorrectPosition from:$from")

        mFrom = from
        mAnimateToCorrectPosition.reset()
        mAnimateToCorrectPosition.duration = ANIMATE_TO_TRIGGER_DURATION.toLong()
        mAnimateToCorrectPosition.interpolator = mDecelerateInterpolator
        if (listener != null) mHeadViewContainer!!.setAnimationListener(listener)
        mHeadViewContainer!!.clearAnimation()
        mHeadViewContainer!!.startAnimation(mAnimateToCorrectPosition)


    }

    private fun animateOffsetToStartPosition(from:Int, listener: Animation.AnimationListener?) {

        log(message = "animateOffsetToStartPosition  from:$from")

        if (mScale) {
            startScaleDownReturnToStartAnimation(from, listener)
        }else {
            mFrom = from
            mAnimateToStartPosition.reset()
            mAnimateToStartPosition.duration = ANIMATE_TO_START_DURATION.toLong()
            mAnimateToStartPosition.interpolator = mDecelerateInterpolator
            if (listener != null) {
                mHeadViewContainer!!.setAnimationListener(listener)
            }
            mHeadViewContainer!!.clearAnimation()
            mHeadViewContainer!!.startAnimation(mAnimateToStartPosition)
            resetTargetLayoutDelay(ANIMATE_TO_START_DURATION)
        }
    }



    /**
     * 重置Target位置
     *
     * @param delay
     */
    private fun resetTargetLayoutDelay(delay:Int) {
        Handler().postDelayed({ resetTargetLayout() }, delay.toLong())
    }



    /**
     * 重置Target的位置
     */
    fun resetTargetLayout() {

        val width = measuredWidth
        val height = measuredHeight
        val child = mTarget
        val childLeft = paddingLeft
        val childTop = paddingTop
        val childWidth = child!!.width - paddingLeft-paddingRight
        val childHeight = child.height - paddingTop-paddingBottom
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)

        val headViewWidth = mHeadViewContainer!!.measuredWidth
        val headViewHeight = mHeadViewContainer!!.measuredHeight
        mHeadViewContainer!!.layout((width / 2 - headViewWidth / 2), -headViewHeight,
                (width / 2 + headViewWidth / 2), 0)// 更新头布局的位置
        val footViewWidth = mFooterViewContainer!!.measuredWidth
        val footViewHeight = mFooterViewContainer!!.measuredHeight
        mFooterViewContainer!!.layout((width / 2 - footViewWidth / 2), height,
                (width / 2 + footViewWidth / 2), height + footViewHeight)

    }

    private val mAnimateToCorrectPosition = object:Animation() {

         override fun applyTransformation(interpolatedTime:Float, t:Transformation) {
            var targetTop = 0
            var endTarget = 0
             endTarget = if (!mUsingCustomStart) {
                 (mSpinnerFinalOffset - Math.abs(mOriginalOffsetTop)).toInt()
             } else {
                 mSpinnerFinalOffset.toInt()
             }
            targetTop = (mFrom + ((endTarget - mFrom) * interpolatedTime).toInt())
            val offset = targetTop - mHeadViewContainer!!.top
            setTargetOffsetTopAndBottom(offset, false /* requires update */)
        }

        override fun setAnimationListener(listener:AnimationListener) {
            super.setAnimationListener(listener)
        }



    }

    private fun moveToStart(interpolatedTime:Float) {
        var targetTop = 0
        targetTop = (mFrom + ((mOriginalOffsetTop - mFrom) * interpolatedTime).toInt())
        val offset = targetTop - mHeadViewContainer!!.top
        setTargetOffsetTopAndBottom(offset, false /* requires update */)


    }

    private val mAnimateToStartPosition = object:Animation() {
         override fun applyTransformation(interpolatedTime:Float, t:Transformation) {
            moveToStart(interpolatedTime)
        }

    }

    private fun startScaleDownReturnToStartAnimation(from:Int, listener: Animation.AnimationListener?) {
        if(mScale){
            startScaleDownReturnToStartAnimation(from, listener)
        }else {
            mFrom = from
            mStartingScale = ViewCompat.getScaleX(mHeadViewContainer)
            mScaleDownToStartAnimation = object : Animation() {
                public override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val targetScale = (mStartingScale + (-mStartingScale * interpolatedTime))
                    setAnimationProgress(targetScale)
                    moveToStart(interpolatedTime)
                }
            }
            mScaleDownToStartAnimation!!.duration = SCALE_DOWN_DURATION.toLong()
            if (listener != null) mHeadViewContainer!!.setAnimationListener(listener)
            mHeadViewContainer!!.clearAnimation()
            mHeadViewContainer!!.startAnimation(mScaleDownToStartAnimation)
        }
    }

    private fun setTargetOffsetTopAndBottom(offset:Int, requiresUpdate:Boolean) {
        mHeadViewContainer!!.bringToFront()
        mHeadViewContainer!!.offsetTopAndBottom(offset)
        mCurrentTargetOffsetTop = mHeadViewContainer!!.top
        if (requiresUpdate && Build.VERSION.SDK_INT < 11) invalidate()
        updateListenerCallBack()

    }



    /**
     * 修改底部布局的位置-敏感pushDistance
     */
    private fun updateFooterViewPosition() {
        mFooterViewContainer!!.visibility = View.VISIBLE
        mFooterViewContainer!!.bringToFront()
        //针对4.4及之前版本的兼容
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) mFooterViewContainer!!.parent.requestLayout()
        mFooterViewContainer!!.offsetTopAndBottom(-pushDistance)
        updatePushDistanceListener()
    }

    private fun updatePushDistanceListener() {
        if (mOnPushLoadMoreListener != null) mOnPushLoadMoreListener!!.onPushDistance(pushDistance)
    }

    private fun onSecondaryPointerUp(ev:MotionEvent) {
        val pointerIndex = MotionEventCompat.getActionIndex(ev)
        val pointerId = MotionEventCompat.getPointerId(ev, pointerIndex)
        if (pointerId == mActivePointerId) {
            val newPointerIndex = if (pointerIndex == 0) 1 else 0
            mActivePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex)
        }

    }




    /**
     * @Description 下拉刷新布局头部的容器
     */
    private inner class HeadViewContainer(context:Context):RelativeLayout(context) {

        private var mListener: Animation.AnimationListener? = null

        fun setAnimationListener(listener: Animation.AnimationListener?) {
            mListener = listener
        }

        public override fun onAnimationStart() {
            super.onAnimationStart()
            log(message = "onAnimationStart animation is null :${animation==null}")

            mListener?.onAnimationStart(this.animation)

        }

        public override fun onAnimationEnd() {
            super.onAnimationEnd()
            log(message = "onAnimationEnd animation is null :${animation==null}")
            mListener?.onAnimationEnd(this.animation)

        }


    }



//    /**
//     * 判断子View是否跟随手指的滑动而滑动，默认跟随
//     *
//     * @return
//     */
//    fun isTargetScrollWithLayout():Boolean = targetScrollWithLayout
//
//
//    /**
//     * 设置子View是否跟谁手指的滑动而滑动
//     *
//     * @param targetScrollWithLayout
//     */
//     fun setTargetScrollWithLayout(targetScrollWithLayout:Boolean) {
//        this.targetScrollWithLayout = targetScrollWithLayout
//    }



    /**
     * 下拉刷新回调
     */
    interface OnPullRefreshListener {
        fun onRefresh()
        fun onPullDistance(distance:Int)
        fun onPullEnable(enable:Boolean)
    }



    /**
     * 上拉加载更多
     */
    interface OnPushLoadMoreListener {
        fun onLoadMore()
        fun onPushDistance(distance:Int)
        fun onPushEnable(enable:Boolean)
    }



    /**
     * Adapter
     */
    inner class OnPullRefreshListenerAdapter:OnPullRefreshListener {

        override fun onRefresh() {

        }

        override fun onPullDistance(distance:Int) {

        }

        override fun onPullEnable(enable:Boolean) {

        }
    }

    inner class OnPushLoadMoreListenerAdapter:OnPushLoadMoreListener {


        override fun onLoadMore() {

        }

        override fun onPushDistance(distance:Int) {

        }

        override fun onPushEnable(enable:Boolean) {

        }

    }


    /**
     * 设置默认下拉刷新进度条的颜色
     *
     * @param color
     */
    fun setDefaultCircleProgressColor(color:Int) {
        if (usingDefaultHeader) defaultProgressView!!.setProgressColor(color)
    }



    /**
     * 设置圆圈的背景色
     *
     * @param color
     */
    fun setDefaultCircleBackgroundColor(color:Int) {
        if (usingDefaultHeader) defaultProgressView!!.setCircleBackgroundColor(color)

    }

     fun setDefaultCircleShadowColor(color:Int) {
         if (usingDefaultHeader) defaultProgressView!!.setShadowColor(color)
     }

    /**
     * 默认的下拉刷新样式
     */
   inner  class CircleProgressView:View, Runnable {
        private var progressPaint:Paint? = null
        private var bgPaint: Paint? = null
//        var width:Int = 0// view的高度
//        private var height:Int = 0// view的宽度

        private var isOnDraw = false
        var isRunning = false
            private set
        private var startAngle = 0
        private var speed = 8
        private var ovalRect: RectF? = null
        private var bgRect:RectF? = null
        private var swipeAngle:Int = 0
        private var progressColor = 0xffcccccc.toInt()
        private var circleBackgroundColor = 0xffffffff.toInt()
        private var shadowColor = 0xff999999.toInt()

        private val PEROID = 16// 绘制周期

        constructor(context:Context) : super(context)
        constructor(context:Context, attrs:AttributeSet) : super(context, attrs)
        constructor(context:Context, attrs:AttributeSet, defStyleAttr:Int) : super(context, attrs, defStyleAttr)


        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawArc(getBgRect(), 0f, 360f, false, createBgPaint())
            val index = startAngle / 360
            swipeAngle = if (index % 2 == 0) {
                (startAngle % 720) / 2
            } else {
                360 - (startAngle % 720) / 2
            }
            canvas.drawArc(getOvalRect(), startAngle.toFloat(), swipeAngle.toFloat(), false,
                    createPaint())


        }




        private fun getBgRect():RectF {
//            width = getWidth()
//            height = getHeight()
            if (bgRect == null) {
                val offset = (density * 2).toInt()
                bgRect = RectF(offset.toFloat(), offset.toFloat(), (width - offset).toFloat(), (height - offset).toFloat())
            }
            return bgRect!!
        }


        private fun getOvalRect():RectF {
//
//            width = getWidth()
//            height = getHeight()
            if (ovalRect == null) {
                val offset = (density * 8).toInt()
                ovalRect = RectF(offset.toFloat(), offset.toFloat(), (width - offset).toFloat(), (height - offset).toFloat())
            }
            return ovalRect!!
        }


        fun setProgressColor(progressColor:Int) {
            this.progressColor = progressColor
        }


        fun setCircleBackgroundColor(circleBackgroundColor:Int) {
            this.circleBackgroundColor = circleBackgroundColor
        }


        fun setShadowColor(shadowColor:Int) {
            this.shadowColor = shadowColor
        }


        /**
         * 根据画笔的颜色，创建画笔
         *
         * @return
         */
        private fun createPaint():Paint {
            if (this.progressPaint == null) {
                progressPaint = Paint()
                progressPaint!!.strokeWidth = (density * 3).toInt().toFloat()
                progressPaint!!.style = Paint.Style.STROKE
                progressPaint!!.isAntiAlias = true
            }
            progressPaint!!.color = progressColor
            return progressPaint!!

        }


        private fun createBgPaint():Paint {
            if (this.bgPaint == null) {
                bgPaint = Paint()
                bgPaint!!.color = circleBackgroundColor
                bgPaint!!.style = Paint.Style.FILL
                bgPaint!!.isAntiAlias = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    this.setLayerType(View.LAYER_TYPE_SOFTWARE, bgPaint)
                }
                bgPaint!!.setShadowLayer(4.0f, 0.0f, 2.0f, shadowColor)
            }
            return bgPaint!!
        }


        fun setPullDistance(distance:Int) {
            this.startAngle = distance * 2
            postInvalidate()
        }

        override fun run() {
            while (isOnDraw) {
                isRunning = true
                val startTime = System.currentTimeMillis()
                startAngle += speed
                postInvalidate()
                val time = System.currentTimeMillis() - startTime
                if (time < PEROID) {
                    try {
                        Thread.sleep(PEROID - time)
                    } catch (e:InterruptedException) {
                        e.printStackTrace()
                    }

                }
            }

        }


        fun setOnDraw(isOnDraw:Boolean) {
            this.isOnDraw = isOnDraw
        }


        fun setSpeed(speed:Int) {
            this.speed = speed
        }




        override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
        }



        override fun onDetachedFromWindow() {
            isOnDraw = false
            super.onDetachedFromWindow()
        }




    }

}


