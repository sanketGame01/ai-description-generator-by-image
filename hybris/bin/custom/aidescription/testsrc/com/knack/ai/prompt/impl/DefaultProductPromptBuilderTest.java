package com.knack.ai.prompt.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.catalog.model.classification.ClassificationAttributeUnitModel;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.classification.ClassificationService;
import de.hybris.platform.classification.features.Feature;
import de.hybris.platform.classification.features.FeatureList;
import de.hybris.platform.classification.features.FeatureValue;
import de.hybris.platform.core.model.product.ProductModel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;


@UnitTest
@RunWith(MockitoJUnitRunner.Silent.class)
public class DefaultProductPromptBuilderTest
{
	private final DefaultProductPromptBuilder builder = new DefaultProductPromptBuilder();

	private ClassificationService classificationService;
	private ProductModel product;
	private FeatureList featureList;

	@Before
	public void setUp()
	{
		classificationService = mock(ClassificationService.class);
		product = mock(ProductModel.class);
		featureList = mock(FeatureList.class);

		builder.setClassificationService(classificationService);
		when(classificationService.getFeatures(product)).thenReturn(featureList);
		when(product.getName()).thenReturn("Tripod X");
		when(product.getManufacturerName()).thenReturn("Sony");
		final List<CategoryModel> categories = List.of(category("Sony"), category("Tripods"));
		when(product.getSupercategories()).thenReturn(categories);
		when(featureList.getFeatures()).thenReturn(List.of());
	}

	@Test
	public void shouldExcludeBrandFromCategories()
	{
		final String prompt = builder.buildPrompt(product);

		assertThat(prompt).contains("Brand: Sony").contains("Category: Tripods").doesNotContain("Category: Sony");
		assertThat(prompt).contains("Specifications:\n- none");
	}

	@Test
	public void shouldFormatNumbersWithoutTrailingZerosAndAppendUnit()
	{
		withFeatures(feature("Weight", new BigDecimal("320.0"), "g"));

		assertThat(builder.buildPrompt(product)).contains("- Weight: 320 g");
	}

	@Test
	public void shouldDropDotAndPercentUnits()
	{
		withFeatures(feature("Zoom", new BigDecimal("3.50"), "."), feature("Humidity", new BigDecimal("45"), "%"));

		final String prompt = builder.buildPrompt(product);

		assertThat(prompt).contains("- Zoom: 3.5\n").contains("- Humidity: 45").doesNotContain("45 %").doesNotContain("3.5 .");
	}

	@Test
	public void shouldNeverAppendUnitToNonNumericValue()
	{
		withFeatures(feature("Battery type", "AA", "pcs"));

		assertThat(builder.buildPrompt(product)).contains("- Battery type: AA").doesNotContain("AA pcs");
	}

	@Test
	public void shouldAppendUnitToNumericTextSuchAsDimensions()
	{
		withFeatures(feature("Dimensions W x D x H", "12 X 2 X 10", "cm"));

		assertThat(builder.getSpecifications(product)).containsExactly("Dimensions W x D x H: 12 X 2 X 10 cm");
	}

	@Test
	public void shouldSkipSourceDataSheetAndEmptyFeatures()
	{
		final Feature empty = mock(Feature.class);
		when(empty.getName()).thenReturn("Empty");
		when(empty.getValues()).thenReturn(List.of());

		final List<Feature> features = List.of(feature("Source data-sheet", "http://x", null), empty,
				feature("Color", "Black", null));
		when(featureList.getFeatures()).thenReturn(features);

		final String prompt = builder.buildPrompt(product);

		assertThat(prompt).doesNotContain("Source data-sheet").doesNotContain("Empty").contains("- Color: Black");
	}

	@Test
	public void shouldReturnSpecificationsWithoutBulletPrefix()
	{
		withFeatures(feature("Weight", new BigDecimal("320.0"), "g"), feature("Color", "Black", null));

		assertThat(builder.getSpecifications(product)).containsExactly("Weight: 320 g", "Color: Black");
	}

	@Test
	public void shouldReturnNoSpecificationsWhenNone()
	{
		assertThat(builder.getSpecifications(product)).isEmpty();
	}

	@Test
	public void shouldUseNaWhenNoBrand()
	{
		when(product.getManufacturerName()).thenReturn(null);

		assertThat(builder.buildPrompt(product)).contains("Brand: n/a");
	}

	@Test
	public void shouldIncludePartNumberOnlyWhenPresent()
	{
		assertThat(builder.buildPrompt(product)).doesNotContain("Part number");

		when(product.getManufacturerAID()).thenReturn("MR-14EX");

		assertThat(builder.buildPrompt(product)).contains("Part number: MR-14EX");
	}

	@Test
	public void shouldIncludeExistingDescriptionWithHtmlStripped()
	{
		when(product.getDescription(Locale.ENGLISH)).thenReturn("<p>Holds <b>3 kg</b></p>&nbsp;");

		final String prompt = builder.buildPrompt(product);

		assertThat(prompt).contains("Existing description (facts only): Holds 3 kg").doesNotContain("<p>").doesNotContain("<b>");
	}

	@Test
	public void shouldContainStyleRules()
	{
		final String prompt = builder.buildPrompt(product);

		assertThat(prompt).contains("lightweight, portable, reliable, sturdy, sleek, perfect, ideal or premium")
				.contains("Do not say who the product is for or how to use it").contains("no bullet points");
	}

	@Test
	public void shouldWrapFactsUnderVerifiedFactsHeading()
	{
		when(product.getManufacturerAID()).thenReturn("MR-14EX");
		when(product.getDescription(Locale.ENGLISH)).thenReturn("Holds 3 kg");
		withFeatures(feature("Weight", new BigDecimal("320.0"), "g"));

		final String prompt = builder.buildPrompt(product);

		assertThat(prompt).contains("Verified facts:\nProduct: Tripod X\nBrand: Sony\nPart number: MR-14EX\n"
				+ "Category: Tripods\nSpecifications:");
	}

	@Test
	public void shouldIncludeConflictResolutionInstruction()
	{
		assertThat(builder.buildPrompt(product))
				.contains("If a value in Specifications conflicts with the Existing description, use the "
						+ "Existing description value.");
	}

	@Test
	public void shouldCountSpecificationsAndDescriptionAsThreeFacts()
	{
		assertThat(builder.countFacts(product)).isZero();

		withFeatures(feature("Color", "Black", null), feature("Weight", new BigDecimal("1"), "kg"));
		assertThat(builder.countFacts(product)).isEqualTo(2);

		when(product.getDescription(Locale.ENGLISH)).thenReturn("Some text");
		assertThat(builder.countFacts(product)).isEqualTo(5);
	}

	@Test
	public void shouldAskForShortTextWithThreeToFiveFacts()
	{
		withFeatures(feature("A", "1", null), feature("B", "2", null), feature("C", "3", null));

		assertThat(builder.buildPrompt(product)).contains("Length: 40-60 words in 1 paragraph.");
	}

	@Test
	public void shouldAskForLongTextWithSixOrMoreFacts()
	{
		withFeatures(feature("A", "1", null), feature("B", "2", null), feature("C", "3", null), feature("D", "4", null),
				feature("E", "5", null), feature("F", "6", null));

		assertThat(builder.buildPrompt(product)).contains("Length: 80-120 words in 2 short paragraphs.");
	}

	@Test
	public void shouldCountDescriptionTowardLongTextThreshold()
	{
		withFeatures(feature("A", "1", null), feature("B", "2", null), feature("C", "3", null));
		when(product.getDescription(Locale.ENGLISH)).thenReturn("Some text");

		assertThat(builder.buildPrompt(product)).contains("Length: 80-120 words in 2 short paragraphs.");
	}

	private void withFeatures(final Feature... features)
	{
		final List<Feature> list = new ArrayList<>(List.of(features));
		when(featureList.getFeatures()).thenReturn(list);
	}

	private static CategoryModel category(final String name)
	{
		final CategoryModel category = mock(CategoryModel.class);
		when(category.getName()).thenReturn(name);
		return category;
	}

	private static Feature feature(final String name, final Object value, final String unitSymbol)
	{
		final FeatureValue featureValue = mock(FeatureValue.class);
		when(featureValue.getValue()).thenReturn(value);
		if (unitSymbol != null)
		{
			final ClassificationAttributeUnitModel unit = mock(ClassificationAttributeUnitModel.class);
			when(unit.getSymbol()).thenReturn(unitSymbol);
			when(featureValue.getUnit()).thenReturn(unit);
		}
		final Feature feature = mock(Feature.class);
		when(feature.getName()).thenReturn(name);
		when(feature.getValues()).thenReturn(List.of(featureValue));
		return feature;
	}
}

