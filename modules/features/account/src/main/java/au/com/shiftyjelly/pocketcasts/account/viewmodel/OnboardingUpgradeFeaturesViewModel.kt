package au.com.shiftyjelly.pocketcasts.account.viewmodel

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.account.onboarding.upgrade.FeatureCardsState
import au.com.shiftyjelly.pocketcasts.account.onboarding.upgrade.UpgradeButton
import au.com.shiftyjelly.pocketcasts.account.onboarding.upgrade.UpgradeFeatureCard
import au.com.shiftyjelly.pocketcasts.account.onboarding.upgrade.toUpgradeButton
import au.com.shiftyjelly.pocketcasts.account.onboarding.upgrade.toUpgradeFeatureCard
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.models.type.Subscription
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionFrequency
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionMapper
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionTier
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.subscription.ProductDetailsState
import au.com.shiftyjelly.pocketcasts.repositories.subscription.PurchaseEvent
import au.com.shiftyjelly.pocketcasts.repositories.subscription.SubscriptionManager
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingFlow
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingUpgradeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import timber.log.Timber

@HiltViewModel
class OnboardingUpgradeFeaturesViewModel @Inject constructor(
    app: Application,
    private val analyticsTracker: AnalyticsTracker,
    private val subscriptionManager: SubscriptionManager,
    private val settings: Settings,
    private val subscriptionMapper: SubscriptionMapper,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val _state: MutableStateFlow<OnboardingUpgradeFeaturesState> = MutableStateFlow(OnboardingUpgradeFeaturesState.Loading)
    val state: StateFlow<OnboardingUpgradeFeaturesState> = _state

    private val source = savedStateHandle.get<OnboardingUpgradeSource>("source") ?: OnboardingUpgradeSource.UNKNOWN
    private val showPatronOnly = savedStateHandle.get<Boolean>("show_patron_only")

    init {
        viewModelScope.launch {
            subscriptionManager
                .observeProductDetails()
                .asFlow()
                .stateIn(viewModelScope)
                .collect { productDetails ->
                    val subscriptions = when (productDetails) {
                        is ProductDetailsState.Failure -> emptyList()
                        is ProductDetailsState.Loaded -> productDetails.productDetails.mapNotNull { productDetailsState ->
                            subscriptionMapper.mapFromProductDetails(
                                productDetails = productDetailsState,
                                isOfferEligible = subscriptionManager.isOfferEligible(
                                    SubscriptionTier.fromProductId(productDetailsState.productId),
                                ),
                            )
                        }
                    }
                    val filteredOffer = Subscription.filterOffers(subscriptions)
                    updateState(filteredOffer)
                }
        }
    }

    private fun updateState(
        subscriptions: List<Subscription>,
    ) {
        val lastSelectedTier = settings.getLastSelectedSubscriptionTier().takeIf { source in listOf(OnboardingUpgradeSource.LOGIN, OnboardingUpgradeSource.PROFILE) }
        val lastSelectedFrequency = settings.getLastSelectedSubscriptionFrequency().takeIf { source in listOf(OnboardingUpgradeSource.LOGIN, OnboardingUpgradeSource.PROFILE) }

        val showPatronOnly = source == OnboardingUpgradeSource.ACCOUNT_DETAILS || showPatronOnly == true
        val fromLogin = source == OnboardingUpgradeSource.LOGIN
        val updatedSubscriptions =
            if (showPatronOnly) {
                subscriptions.filter { it.tier == SubscriptionTier.PATRON }
            } else {
                subscriptions
            }

        val selectedSubscription = subscriptionManager.getDefaultSubscription(
            subscriptions = updatedSubscriptions,
            tier = if (showPatronOnly) SubscriptionTier.PATRON else { if (fromLogin) lastSelectedTier else null },
            frequency = if (fromLogin) lastSelectedFrequency else null,
        )

        val showNotNow = source == OnboardingUpgradeSource.RECOMMENDATIONS

        selectedSubscription?.let {
            val currentSubscriptionFrequency = selectedSubscription.recurringPricingPhase.toSubscriptionFrequency()
            val currentTier = SubscriptionTier.fromProductId(selectedSubscription.productDetails.productId)
            val currentFeatureCard = currentTier.toUpgradeFeatureCard()
            _state.update {
                OnboardingUpgradeFeaturesState.Loaded(
                    featureCardsState = FeatureCardsState(
                        subscriptions = updatedSubscriptions,
                        currentFeatureCard = currentFeatureCard,
                        currentFrequency = currentSubscriptionFrequency,
                    ),
                    currentSubscription = selectedSubscription,
                    currentFeatureCard = currentFeatureCard,
                    currentSubscriptionFrequency = currentSubscriptionFrequency,
                    showNotNow = showNotNow,
                )
            }
        } ?: _state.update { // In ideal world, we should never get here
            OnboardingUpgradeFeaturesState.NoSubscriptions(showNotNow)
        }
    }

    fun onShown(flow: OnboardingFlow, source: OnboardingUpgradeSource) {
        analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_SHOWN, analyticsProps(flow, source))
    }

    fun onDismiss(flow: OnboardingFlow, source: OnboardingUpgradeSource) {
        analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_DISMISSED, analyticsProps(flow, source))
    }

    fun onNotNow(flow: OnboardingFlow, source: OnboardingUpgradeSource) {
        analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_NOT_NOW_BUTTON_TAPPED, analyticsProps(flow, source))
    }

    fun onSubscriptionFrequencyChanged(frequency: SubscriptionFrequency) {
        (_state.value as? OnboardingUpgradeFeaturesState.Loaded)?.let { loadedState ->
            val currentSubscription = subscriptionManager
                .getDefaultSubscription(
                    subscriptions = loadedState.featureCardsState.subscriptions,
                    tier = loadedState.currentFeatureCard.subscriptionTier,
                    frequency = frequency,
                )
            settings.setLastSelectedSubscriptionFrequency(frequency)
            analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_SUBSCRIPTION_FREQUENCY_CHANGED, mapOf("value" to frequency.name.lowercase()))
            currentSubscription?.let {
                _state.update {
                    loadedState.copy(
                        currentSubscription = currentSubscription,
                        currentSubscriptionFrequency = frequency,
                    )
                }
            }
        }
    }

    fun onFeatureCardChanged(upgradeFeatureCard: UpgradeFeatureCard) {
        (_state.value as? OnboardingUpgradeFeaturesState.Loaded)?.let { loadedState ->
            val currentSubscription = subscriptionManager
                .getDefaultSubscription(
                    subscriptions = loadedState.featureCardsState.subscriptions,
                    tier = upgradeFeatureCard.subscriptionTier,
                    frequency = loadedState.currentSubscriptionFrequency,
                )
            analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_SUBSCRIPTION_TIER_CHANGED, mapOf("value" to upgradeFeatureCard.subscriptionTier.name.lowercase()))
            settings.setLastSelectedSubscriptionTier(upgradeFeatureCard.subscriptionTier)
            currentSubscription?.let {
                _state.update {
                    loadedState.copy(
                        currentSubscription = currentSubscription,
                        currentFeatureCard = upgradeFeatureCard,
                    )
                }
            }
        }
    }

    fun onClickSubscribe(
        activity: AppCompatActivity,
        flow: OnboardingFlow,
        source: OnboardingUpgradeSource,
        onComplete: () -> Unit,
    ) {
        (state.value as? OnboardingUpgradeFeaturesState.Loaded)?.let { loadedState ->
            _state.update { loadedState.copy(purchaseFailed = false) }
            val currentSubscription = subscriptionManager
                .getDefaultSubscription(
                    subscriptions = loadedState.featureCardsState.subscriptions,
                    tier = loadedState.currentFeatureCard.subscriptionTier,
                    frequency = loadedState.currentSubscriptionFrequency,
                )

            currentSubscription?.let { subscription ->
                analyticsTracker.track(
                    AnalyticsEvent.SELECT_PAYMENT_FREQUENCY_NEXT_BUTTON_TAPPED,
                    mapOf(
                        OnboardingUpgradeBottomSheetViewModel.flowKey to flow.analyticsValue,
                        OnboardingUpgradeBottomSheetViewModel.sourceKey to source.analyticsValue,
                        OnboardingUpgradeBottomSheetViewModel.selectedSubscriptionKey to subscription.productDetails.productId,
                    ),
                )

                viewModelScope.launch {
                    val purchaseEvent = subscriptionManager
                        .observePurchaseEvents()
                        .asFlow()
                        .firstOrNull()

                    when (purchaseEvent) {
                        PurchaseEvent.Success -> {
                            onComplete()
                        }

                        is PurchaseEvent.Cancelled -> {
                            // User cancelled subscription creation. Do nothing.
                        }

                        is PurchaseEvent.Failure -> {
                            _state.update { loadedState.copy(purchaseFailed = true) }
                        }

                        null -> {
                            Timber.e("Purchase event was null. This should never happen.")
                        }
                    }

                    if (purchaseEvent != null) {
                        CreateAccountViewModel.trackPurchaseEvent(
                            subscription,
                            purchaseEvent,
                            analyticsTracker,
                        )
                    }
                }
                subscriptionManager.launchBillingFlow(
                    activity,
                    subscription.productDetails,
                    subscription.offerToken,
                )
            }
        }
    }

    fun onRateUsPressed() {
        analyticsTracker.track(AnalyticsEvent.RATE_US_TAPPED, mapOf("source" to OnboardingUpgradeSource.PLUS_DETAILS.analyticsValue))
    }

    fun onPrivacyPolicyPressed() {
        analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_PRIVACY_POLICY_TAPPED)
    }

    fun onTermsAndConditionsPressed() {
        analyticsTracker.track(AnalyticsEvent.PLUS_PROMOTION_TERMS_AND_CONDITIONS_TAPPED)
    }

    companion object {
        private fun analyticsProps(flow: OnboardingFlow, source: OnboardingUpgradeSource) =
            mapOf("flow" to flow.analyticsValue, "source" to source.analyticsValue)
    }
}

sealed class OnboardingUpgradeFeaturesState {
    data object Loading : OnboardingUpgradeFeaturesState()

    data class NoSubscriptions(val showNotNow: Boolean) : OnboardingUpgradeFeaturesState()

    data class Loaded(
        val currentFeatureCard: UpgradeFeatureCard,
        val featureCardsState: FeatureCardsState,
        val currentSubscriptionFrequency: SubscriptionFrequency,
        val currentSubscription: Subscription,
        val purchaseFailed: Boolean = false,
        val showNotNow: Boolean,
    ) : OnboardingUpgradeFeaturesState() {
        val subscriptionFrequencies =
            listOf(SubscriptionFrequency.YEARLY, SubscriptionFrequency.MONTHLY)
        val currentUpgradeButton: UpgradeButton
            get() = currentSubscription.toUpgradeButton()
    }
}
