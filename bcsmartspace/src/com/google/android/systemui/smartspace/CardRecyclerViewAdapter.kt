package com.google.android.systemui.smartspace

import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.SmartspaceUtils
import android.app.smartspace.uitemplatedata.BaseTemplateData
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.android.internal.graphics.ColorUtils
import com.android.launcher3.icons.GraphicsUtils
import com.android.systemui.bcsmartspace.R
import com.android.systemui.plugins.BcSmartspaceConfigPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard

class CardRecyclerViewAdapter(
    private val root: BcSmartspaceView,
    @VisibleForTesting var configProvider: BcSmartspaceConfigPlugin,
) : ListAdapter<SmartspaceTarget, CardRecyclerViewAdapter.ViewHolder>(DiffUtilItemCallback()) {

    private val viewPager2 = root.findViewById<View>(R.id.smartspace_card_pager) as? ViewPager2
    val viewHolders = SparseArray<ViewHolder>()
    private val backgroundOutlineDrawable =
        root.context.getDrawable(R.drawable.bg_non_remoteviews_card_outline)
    private val backgroundDrawable = root.context.getDrawable(R.drawable.bg_non_remoteviews_card)
    private val solidBackgroundDrawable: GradientDrawable? =
        try {
            getSolidBackgroundDrawable()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to get solid background drawable", e)
            null
        }

    var smartspaceTargets: List<SmartspaceTarget> = ArrayList()
        private set

    private val _aodTargets = ArrayList<SmartspaceTarget>()
    private val _lockscreenTargets = ArrayList<SmartspaceTarget>()
    private val mediaTargets = ArrayList<SmartspaceTarget>()
    private var _dozeAmount = 0f
    private var _isBackgroundEnabled = false
    private val dozeColor = -1

    @VisibleForTesting
    var primaryTextColor = GraphicsUtils.getAttrColor(root.context, android.R.attr.textColorPrimary)
        set(value) {
            field = value
            updateCurrentTextColor()
        }

    private val textColorOnBg = -1
    private var currentTextColor = if (_isBackgroundEnabled) textColorOnBg else primaryTextColor
    private val bgNonRemoteViewsHorizontalPadding =
        root.context.resources.getDimensionPixelSize(
            R.dimen.bg_non_remoteviews_card_padding_horizontal
        )
    private val defaultNonRemoteViewsPaddingStart =
        root.context.resources.getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start)
    private var transitioningTo = TransitionType.NOT_IN_TRANSITION
    private var previousDozeAmount = 0f
    private var currentBackgroundDrawable: Drawable? = null

    var bgHandler: Handler? = null

    @VisibleForTesting var dataProvider: BcSmartspaceDataPlugin? = null

    var timeChangedDelegate: BcSmartspaceDataPlugin.TimeChangedDelegate? = null

    @VisibleForTesting var uiSurface: String? = null

    @VisibleForTesting
    var hasDifferentTargets = false
        private set

    @VisibleForTesting
    var hasAodLockscreenTransition = false
        private set

    @VisibleForTesting
    var keyguardBypassEnabled = false
        set(value) {
            field = value
            updateTargetVisibility()
        }

    @VisibleForTesting
    var screenOn = false
        set(value) {
            field = value
            for (i in 0 until viewHolders.size()) {
                viewHolders.get(viewHolders.keyAt(i))?.card?.setScreenOn(value)
            }
        }

    var nonRemoteViewsHorizontalPadding: Int? = null
        set(value) {
            field = value
            if (!_isBackgroundEnabled) {
                refreshCardPaddings()
            }
        }

    var isBackgroundEnabled: Boolean
        get() = _isBackgroundEnabled
        set(value) {
            _isBackgroundEnabled = value
            refreshCardBackground()
            refreshCardPaddings()
            updateCurrentTextColor()
        }

    @VisibleForTesting
    var dozeAmount: Float
        get() = _dozeAmount
        set(value) {
            _dozeAmount = value
            transitioningTo =
                when {
                    previousDozeAmount > value -> TransitionType.TO_LOCKSCREEN
                    previousDozeAmount < value -> TransitionType.TO_AOD
                    else -> TransitionType.NOT_IN_TRANSITION
                }
            previousDozeAmount = value
            updateTargetVisibility()
            if (currentBackgroundDrawable != backgroundOutlineDrawable && needToSetToAodTargets()) {
                currentBackgroundDrawable = backgroundOutlineDrawable
                refreshCardBackground()
            } else if (
                currentBackgroundDrawable != backgroundDrawable && needToSetToLockscreenTargets()
            ) {
                currentBackgroundDrawable = backgroundDrawable
                refreshCardBackground()
            }
            updateCurrentTextColor()
        }

    @VisibleForTesting
    val aodTargets: List<SmartspaceTarget>
        get() =
            when {
                mediaTargets.isNotEmpty() -> mediaTargets
                hasDifferentTargets -> _aodTargets
                else -> lockscreenTargets
            }

    @VisibleForTesting
    val lockscreenTargets: List<SmartspaceTarget>
        get() =
            if (mediaTargets.isEmpty() || !keyguardBypassEnabled) _lockscreenTargets
            else mediaTargets

    class DiffUtilItemCallback : DiffUtil.ItemCallback<SmartspaceTarget>() {
        override fun areItemsTheSame(oldItem: SmartspaceTarget, newItem: SmartspaceTarget) =
            oldItem.smartspaceTargetId == newItem.smartspaceTargetId

        override fun areContentsTheSame(oldItem: SmartspaceTarget, newItem: SmartspaceTarget) =
            false
    }

    enum class TransitionType {
        NOT_IN_TRANSITION,
        TO_LOCKSCREEN,
        TO_AOD,
    }

    class ViewHolder(itemView: View, val card: SmartspaceCard) : RecyclerView.ViewHolder(itemView) {
        fun setBackground(drawable: Drawable?) {
            if (itemView is BcSmartspaceRemoteViewsCard) {
                return
            }
            (itemView as? ViewGroup)?.getChildAt(0)?.background = drawable
        }
    }

    override fun getItemViewType(position: Int): Int {
        val target = getItem(position)
        val remoteViews = target.remoteViews
        val templateData = target.templateData
        if (remoteViews != null) {
            return remoteViews.layoutId
        }
        if (!isTemplateCard(target)) {
            return BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP[
                    BcSmartSpaceUtil.getFeatureType(target)] ?: R.layout.smartspace_card
        }
        val primaryItem = templateData!!.primaryItem
        if (
            primaryItem == null ||
                (SmartspaceUtils.isEmpty(primaryItem.text) && primaryItem.icon == null) ||
                primaryItem.loggingInfo?.featureType == 1
        ) {
            return R.layout.smartspace_base_template_card_with_date
        }
        return BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES[
                templateData.templateType] ?: R.layout.smartspace_base_template_card
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card: SmartspaceCard
        if (
            templateSecondaryCardResourceIdSet.contains(viewType) ||
                viewType == R.layout.smartspace_base_template_card_with_date ||
                viewType == R.layout.smartspace_base_template_card
        ) {
            var layoutId = viewType
            var secondaryCardResId: Int? = null
            if (templateSecondaryCardResourceIdSet.contains(viewType)) {
                secondaryCardResId = viewType
                layoutId = R.layout.smartspace_base_template_card
            }
            val inflater = LayoutInflater.from(parent.context)
            val templateCard = inflater.inflate(layoutId, parent, false) as BaseTemplateCard
            templateCard.mUiSurface = uiSurface
            val dateView = templateCard.mDateView
            if (
                dateView != null &&
                    TextUtils.equals(uiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)
            ) {
                check(!dateView.isAttachedToWindow) { "Must call before attaching view to window." }
                dateView.mUpdatesOnAod = true
            }
            if (dateView != null) {
                check(!dateView.isAttachedToWindow) { "Must call before attaching view to window." }
                dateView.mTimeChangedDelegate = timeChangedDelegate
            }
            templateCard.layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            if (secondaryCardResId != null) {
                val secondaryCard =
                    inflater.inflate(secondaryCardResId, templateCard, false)
                        as BcSmartspaceCardSecondary?
                Log.i(TAG, "Secondary card is found")
                val secondaryCardPane = templateCard.mSecondaryCardPane
                if (secondaryCardPane != null) {
                    templateCard.mSecondaryCard = secondaryCard
                    BcSmartspaceTemplateDataUtils.updateVisibility(secondaryCardPane, View.GONE)
                    secondaryCardPane.removeAllViews()
                    if (secondaryCard != null) {
                        secondaryCardPane.addView(
                            secondaryCard,
                            createSecondaryCardLayoutParams(templateCard),
                        )
                    }
                }
            }
            card = templateCard
        } else if (
            legacySecondaryCardResourceIdSet.contains(viewType) ||
                viewType == R.layout.smartspace_card
        ) {
            var layoutId = viewType
            var secondaryCardResId: Int? = null
            if (legacySecondaryCardResourceIdSet.contains(viewType)) {
                secondaryCardResId = viewType
                layoutId = R.layout.smartspace_card
            }
            val inflater = LayoutInflater.from(parent.context)
            val legacyCard = inflater.inflate(layoutId, parent, false) as BcSmartspaceCard
            legacyCard.mUiSurface = uiSurface
            legacyCard.layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            if (secondaryCardResId != null) {
                val secondaryCard =
                    inflater.inflate(secondaryCardResId, legacyCard, false)
                        as BcSmartspaceCardSecondary?
                val secondaryCardGroup = legacyCard.mSecondaryCardGroup
                if (secondaryCardGroup != null) {
                    legacyCard.mSecondaryCard = secondaryCard
                    BcSmartspaceTemplateDataUtils.updateVisibility(secondaryCardGroup, View.GONE)
                    secondaryCardGroup.removeAllViews()
                    if (secondaryCard != null) {
                        secondaryCardGroup.addView(
                            secondaryCard,
                            createSecondaryCardLayoutParams(legacyCard),
                        )
                    }
                }
            }
            card = legacyCard
        } else {
            val remoteViewsCard = BcSmartspaceRemoteViewsCard(parent.context)
            remoteViewsCard._uiSurface = uiSurface
            remoteViewsCard.layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            card = remoteViewsCard
        }
        val itemView =
            if (card is BcSmartspaceRemoteViewsCard) {
                card
            } else {
                FrameLayout(parent.context).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    val backgroundView = View(parent.context)
                    backgroundView.layoutParams =
                        ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            .apply {
                                topMargin =
                                    backgroundView.context.resources.getDimensionPixelSize(
                                        R.dimen.background_top_padding
                                    )
                                bottomMargin =
                                    backgroundView.context.resources.getDimensionPixelSize(
                                        R.dimen.background_bottom_padding
                                    )
                            }
                    addView(backgroundView)
                    addView(card.view)
                }
            }
        return ViewHolder(itemView, card)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val target = getItem(position)
        val isRemoteViews = target.remoteViews != null
        val isTemplateCard = isTemplateCard(target)
        val loggingInfo =
            BcSmartspaceCardLoggingInfo(
                BcSmartspaceCardLoggingInfo.Builder()
                    .setInstanceId(InstanceId.create(target))
                    .setFeatureType(target.featureType)
                    .setDisplaySurface(
                        BcSmartSpaceUtil.getLoggingDisplaySurface(uiSurface, _dozeAmount)
                    )
                    .setRank(position)
                    .setCardinality(smartspaceTargets.size)
                    .setUid(-1)
                    .setSubcardInfo(
                        if (isTemplateCard) {
                            BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(target.templateData)
                        } else {
                            BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(target)
                        }
                    )
                    .setDimensionalInfo(
                        BcSmartspaceCardLoggerUtil.createDimensionalLoggingInfo(target.templateData)
                    )
            )
        val card = holder.card
        if (isRemoteViews) {
            if (card !is BcSmartspaceRemoteViewsCard) {
                Log.w(TAG, "[rmv] No RemoteViews card view can be binded")
                return
            }
            Log.d(TAG, "[rmv] Refreshing RemoteViews card")
        } else if (isTemplateCard) {
            val templateData = checkNotNull(target.templateData)
            BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureTypeOrUpdateLogInfoFromTemplateData(
                loggingInfo,
                templateData,
            )
            if (card !is BaseTemplateCard) {
                Log.w(TAG, "No ui-template card view can be binded")
                return
            }
            card.mBgHandler = bgHandler
            card.mDateView?.mBgHandler = bgHandler
            card.setPaddingRelative(
                getNonRemoteViewsPaddingStart(),
                card.paddingTop,
                getNonRemoteViewsPaddingEnd(),
                card.paddingBottom,
            )
        } else {
            if (card !is BcSmartspaceCard) {
                Log.w(TAG, "No legacy card view can be binded")
                return
            }
            card.setPaddingRelative(
                getNonRemoteViewsPaddingStart(),
                card.paddingTop,
                getNonRemoteViewsPaddingEnd(),
                card.paddingBottom,
            )
        }
        card.bindData(target, dataProvider?.eventNotifier, loggingInfo, smartspaceTargets.size > 1)
        holder.setBackground(if (_isBackgroundEnabled) currentBackgroundDrawable else null)
        card.setPrimaryTextColor(currentTextColor)
        card.setDozeAmount(_dozeAmount)
        viewHolders.put(position, holder)
    }

    fun getLegacyCardAtPosition(position: Int): BcSmartspaceCard? =
        viewHolders.get(position)?.card as? BcSmartspaceCard

    fun getTemplateCardAtPosition(position: Int): BaseTemplateCard? =
        viewHolders.get(position)?.card as? BaseTemplateCard

    fun getTargetAtPosition(position: Int): SmartspaceTarget? {
        if (position < 0 || position >= itemCount) {
            return null
        }
        return getItem(position)
    }

    fun setMediaTarget(target: SmartspaceTarget?) {
        mediaTargets.clear()
        if (target != null) {
            mediaTargets.add(target)
        }
        updateTargetVisibility(null, true)
    }

    fun setTargets(targets: List<Parcelable>, runnable: Runnable?) {
        _aodTargets.clear()
        _lockscreenTargets.clear()
        hasDifferentTargets = false
        for (parcelable in targets) {
            val target = parcelable as SmartspaceTarget
            if (
                target.featureType == 34 ||
                    (target.remoteViews == null &&
                        !isTemplateCard(target) &&
                        target.featureType == 1)
            ) {
                Log.e(TAG, "No card can be created for target: " + target.featureType)
                continue
            }
            val screenExtra = target.baseAction?.extras?.getInt("SCREEN_EXTRA", 3) ?: 3
            if ((screenExtra and 2) != 0) {
                _aodTargets.add(target)
            }
            if ((screenExtra and 1) != 0) {
                _lockscreenTargets.add(target)
            }
            if (screenExtra != 3) {
                hasDifferentTargets = true
            }
        }
        if (uiSurface.equals(BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN, false)) {
            addDefaultDateCardIfEmpty(_aodTargets)
            addDefaultDateCardIfEmpty(_lockscreenTargets)
        }
        updateTargetVisibility(runnable, true)
    }

    fun needToSetToLockscreenTargets(): Boolean {
        if (_dozeAmount == 0f) {
            return true
        }
        return 1f - _dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_LOCKSCREEN
    }

    private fun needToSetToAodTargets() =
        _dozeAmount == 1f || (_dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_AOD)

    private fun updateTargetVisibility(runnable: Runnable? = null, forceUpdate: Boolean = false) {
        val aodTargets = aodTargets
        val lockscreenTargets = lockscreenTargets
        val updateToAod = smartspaceTargets !== aodTargets && needToSetToAodTargets()
        val updateToLockscreen =
            smartspaceTargets !== lockscreenTargets && needToSetToLockscreenTargets()
        if (updateToAod) {
            Log.d(TAG, "Updating Smartspace targets to targets for AOD")
            smartspaceTargets = aodTargets
        } else if (updateToLockscreen) {
            Log.d(TAG, "Updating Smartspace targets to targets for Lockscreen")
            smartspaceTargets = lockscreenTargets
        }
        if (forceUpdate || updateToAod || updateToLockscreen) {
            viewHolders.clear()
            resetSelectedPageIfNeeded()
            submitList(smartspaceTargets.toList(), runnable)
        }
        hasAodLockscreenTransition = aodTargets !== lockscreenTargets
        if (
            uiSurface == null ||
                uiSurface.equals(BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN, false)
        ) {
            return
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(
            root,
            if (smartspaceTargets.isEmpty()) View.GONE else View.VISIBLE,
        )
    }

    private fun resetSelectedPageIfNeeded() {
        val currentList = currentList
        if (
            currentList.isEmpty() ||
                smartspaceTargets.isEmpty() ||
                (currentList.size <= 1 && smartspaceTargets.size <= 1)
        ) {
            return
        }
        if (root.mScrollState != ViewPager2.SCROLL_STATE_IDLE) {
            return
        }
        if (root.mViewPager2.currentItem != 0) {
            return
        }
        if (
            currentList.firstOrNull()?.smartspaceTargetId !=
                smartspaceTargets.firstOrNull()?.smartspaceTargetId
        ) {
            viewPager2?.doOnNextLayout { (it as ViewPager2).setCurrentItem(0, false) }
        }
    }

    private fun addDefaultDateCardIfEmpty(targets: MutableList<SmartspaceTarget>) {
        if (targets.isEmpty()) {
            targets.add(
                SmartspaceTarget.Builder(
                        "date_card_794317_92634",
                        ComponentName(root.context, CardRecyclerViewAdapter::class.java),
                        root.context.user,
                    )
                    .setFeatureType(1)
                    .setTemplateData(BaseTemplateData.Builder(1).build())
                    .build()
            )
        }
    }

    private fun getNonRemoteViewsPaddingStart(): Int {
        if (_isBackgroundEnabled) {
            return bgNonRemoteViewsHorizontalPadding
        }
        return nonRemoteViewsHorizontalPadding ?: defaultNonRemoteViewsPaddingStart
    }

    private fun getNonRemoteViewsPaddingEnd(): Int {
        if (_isBackgroundEnabled) {
            return bgNonRemoteViewsHorizontalPadding
        }
        return nonRemoteViewsHorizontalPadding ?: 0
    }

    private fun getSolidBackgroundDrawable(): GradientDrawable {
        solidBackgroundDrawable?.let {
            return it
        }
        checkNotNull(backgroundDrawable) { "Background drawable is null" }
        check(backgroundDrawable is LayerDrawable) { "Background drawable isn't a LayerDrawable" }
        val solid =
            checkNotNull(backgroundDrawable.findDrawableByLayerId(R.id.solid)) {
                "Solid background drawable is null"
            }
        check(solid is GradientDrawable) { "Solid background drawable isn't a LayerDrawable" }
        return solid
    }

    private fun refreshCardBackground() {
        for (i in 0 until viewHolders.size()) {
            viewHolders
                .get(viewHolders.keyAt(i))
                ?.setBackground(if (_isBackgroundEnabled) currentBackgroundDrawable else null)
        }
    }

    private fun refreshCardPaddings() {
        val paddingStart = getNonRemoteViewsPaddingStart()
        val paddingEnd = getNonRemoteViewsPaddingEnd()
        for (i in 0 until viewHolders.size()) {
            val position = viewHolders.keyAt(i)
            getLegacyCardAtPosition(position)?.let {
                it.setPaddingRelative(paddingStart, it.paddingTop, paddingEnd, it.paddingBottom)
            }
            getTemplateCardAtPosition(position)?.let {
                it.setPaddingRelative(paddingStart, it.paddingTop, paddingEnd, it.paddingBottom)
            }
        }
    }

    private fun updateCurrentTextColor() {
        currentTextColor =
            ColorUtils.blendARGB(
                if (_isBackgroundEnabled) textColorOnBg else primaryTextColor,
                dozeColor,
                _dozeAmount,
            )
        for (i in 0 until viewHolders.size()) {
            viewHolders.get(viewHolders.keyAt(i))?.card?.let {
                it.setPrimaryTextColor(currentTextColor)
                it.setDozeAmount(_dozeAmount)
            }
        }
    }

    private fun createSecondaryCardLayoutParams(card: View) =
        ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                card.resources.getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height),
            )
            .apply {
                marginStart =
                    card.resources.getDimensionPixelSize(
                        R.dimen.enhanced_smartspace_secondary_card_start_margin
                    )
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }

    companion object {
        private const val TAG = "SsCardRecyclerViewAdapter"

        private val legacySecondaryCardResourceIdSet =
            BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP.values.toSet()
        private val templateSecondaryCardResourceIdSet =
            BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES.values.toSet()

        private fun isTemplateCard(target: SmartspaceTarget) =
            target.templateData != null &&
                BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.templateData)
    }
}
