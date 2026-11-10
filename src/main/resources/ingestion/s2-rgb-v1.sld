<?xml version="1.0" encoding="UTF-8"?>
<StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" xmlns:ogc="http://www.opengis.net/ogc">
  <NamedLayer><Name>s2-rgb-v1</Name><UserStyle><Name>s2-rgb-v1</Name><Title>Sentinel-2 reflectance RGB 3/2/1</Title>
    <FeatureTypeStyle><Rule><RasterSymbolizer><Opacity>1.0</Opacity>
      <ChannelSelection>
        <RedChannel><SourceChannelName>3</SourceChannelName><ContrastEnhancement><Normalize/></ContrastEnhancement></RedChannel>
        <GreenChannel><SourceChannelName>2</SourceChannelName><ContrastEnhancement><Normalize/></ContrastEnhancement></GreenChannel>
        <BlueChannel><SourceChannelName>1</SourceChannelName><ContrastEnhancement><Normalize/></ContrastEnhancement></BlueChannel>
      </ChannelSelection>
    </RasterSymbolizer></Rule></FeatureTypeStyle>
  </UserStyle></NamedLayer>
</StyledLayerDescriptor>
