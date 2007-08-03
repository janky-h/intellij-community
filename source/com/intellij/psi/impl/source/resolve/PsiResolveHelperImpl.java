package com.intellij.psi.impl.source.resolve;

import com.intellij.openapi.util.Pair;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.DummyHolder;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.parsing.Parsing;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.impl.source.tree.FileElement;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.scope.MethodProcessorSetupFailedException;
import com.intellij.psi.scope.processor.MethodCandidatesProcessor;
import com.intellij.psi.scope.processor.MethodResolverProcessor;
import com.intellij.psi.scope.util.PsiScopesUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

public class PsiResolveHelperImpl implements PsiResolveHelper {
  private final PsiManager myManager;

  public PsiResolveHelperImpl(PsiManager manager) {
    myManager = manager;
  }

  @NotNull
  public JavaResolveResult resolveConstructor(PsiClassType classType, PsiExpressionList argumentList, PsiElement place) {
    JavaResolveResult[] result = multiResolveConstructor(classType, argumentList, place);
    if (result.length != 1) return JavaResolveResult.EMPTY;
    return result[0];
  }

  @NotNull
  public JavaResolveResult[] multiResolveConstructor(PsiClassType type, PsiExpressionList argumentList, PsiElement place) {
    PsiClassType.ClassResolveResult classResolveResult = type.resolveGenerics();
    PsiClass aClass = classResolveResult.getElement();
    if (aClass == null) {
      return JavaResolveResult.EMPTY_ARRAY;
    }
    final MethodResolverProcessor processor;
    PsiSubstitutor substitutor = classResolveResult.getSubstitutor();
    if (argumentList.getParent() instanceof PsiAnonymousClass) {
      final PsiAnonymousClass anonymous = (PsiAnonymousClass)argumentList.getParent();
      processor = new MethodResolverProcessor(anonymous, argumentList, place);
      aClass = anonymous.getBaseClassType().resolve();
      if (aClass == null) return JavaResolveResult.EMPTY_ARRAY;
      substitutor = substitutor.putAll(TypeConversionUtil.getSuperClassSubstitutor(aClass, anonymous, substitutor));
    }
    else {
      processor = new MethodResolverProcessor(aClass, argumentList, place);
    }

    for (PsiMethod constructor : aClass.getConstructors()) {
      if (!processor.execute(constructor, substitutor)) break;
    }

    return processor.getResult();
  }

  public PsiClass resolveReferencedClass(String referenceText, PsiElement context) {
    final FileElement holderElement = new DummyHolder(myManager, context).getTreeElement();
    CompositeElement ref = Parsing.parseJavaCodeReferenceText(myManager, referenceText, holderElement.getCharTable());
    if (ref == null) return null;
    TreeUtil.addChildren(holderElement, ref);

    return ResolveClassUtil.resolveClass((PsiJavaCodeReferenceElement)ref.getPsi());
  }

  public PsiVariable resolveReferencedVariable(String referenceText, PsiElement context) {
    final FileElement holderElement = new DummyHolder(myManager, context).getTreeElement();
    TreeElement ref = Parsing.parseJavaCodeReferenceText(myManager, referenceText, holderElement.getCharTable());
    if (ref == null) return null;
    TreeUtil.addChildren(holderElement, ref);
    PsiJavaCodeReferenceElement psiRef = (PsiJavaCodeReferenceElement)SourceTreeToPsiMap.treeElementToPsi(ref);
    return ResolveVariableUtil.resolveVariable(psiRef, null, null);
  }

  public boolean isAccessible(PsiMember member, PsiElement place, @Nullable PsiClass accessObjectClass) {
    return isAccessible(member, member.getModifierList(), place, accessObjectClass, null);
  }


  public boolean isAccessible(PsiMember member,
                              PsiModifierList modifierList,
                              PsiElement place,
                              @Nullable PsiClass accessObjectClass,
                              final PsiElement currentFileResolveScope) {
    return ResolveUtil.isAccessible(member, member.getContainingClass(), modifierList, place, accessObjectClass, currentFileResolveScope);
  }

  @NotNull
  public CandidateInfo[] getReferencedMethodCandidates(PsiCallExpression expr, boolean dummyImplicitConstructor) {
    final MethodCandidatesProcessor processor = new MethodCandidatesProcessor(expr);
    try {
      PsiScopesUtil.setupAndRunProcessor(processor, expr, dummyImplicitConstructor);
    }
    catch (MethodProcessorSetupFailedException e) {
      return CandidateInfo.EMPTY_ARRAY;
    }
    return processor.getCandidates();
  }

  private Pair<PsiType, ConstraintType> inferTypeForMethodTypeParameterInner(
                                                 final PsiTypeParameter typeParameter,
                                                 final PsiParameter[] parameters,
                                                 PsiExpression[] arguments,
                                                 PsiSubstitutor partialSubstitutor,
                                                 PsiElement parent,
                                                 final boolean forCompletion) {
    PsiWildcardType wildcardToCapture = null;
    PsiType lowerBound = PsiType.NULL;
    PsiType upperBound = PsiType.NULL;
    if (parameters.length > 0) {
      for (int j = 0; j < arguments.length; j++) {
        PsiExpression argument = arguments[j];
        if (argument instanceof PsiMethodCallExpression &&
          myBlockedForInferenceMethodCalls.get().contains(argument)) continue;

        final PsiParameter parameter = parameters[Math.min(j, parameters.length - 1)];
        if (j >= parameters.length && !parameter.isVarArgs()) break;
        PsiType parameterType = parameter.getType();
        PsiType argumentType = argument.getType();
        if (argumentType == null) continue;

        if (parameterType instanceof PsiEllipsisType) {
          parameterType = ((PsiEllipsisType)parameterType).getComponentType();
          if (arguments.length == parameters.length && argumentType instanceof PsiArrayType && !(((PsiArrayType)argumentType).getComponentType() instanceof PsiPrimitiveType)) {
            argumentType = ((PsiArrayType)argumentType).getComponentType();
          }
        }
        final Pair<PsiType,ConstraintType> currentSubstitution = getSubstitutionForTypeParameterConstraint(typeParameter, parameterType,
                                                                            argumentType, true, PsiUtil.getLanguageLevel(argument));
        if (currentSubstitution == null) continue;
        if (currentSubstitution == FAILED_INFERENCE) {
          return getFailedInferenceConstraint(typeParameter);
        }

        final ConstraintType constraintType = currentSubstitution.getSecond();
        final PsiType type = currentSubstitution.getFirst();
        if (type == null) return null;
        switch(constraintType) {
          case EQUALS:
            if (!(type instanceof PsiWildcardType)) return currentSubstitution;
            if (wildcardToCapture != null) return getFailedInferenceConstraint(typeParameter);
            wildcardToCapture = (PsiWildcardType) type;
            break;
          case SUPERTYPE:
            if (lowerBound == PsiType.NULL) {
              lowerBound = type;
            } else {
              if (!lowerBound.equals(type)) {
                lowerBound = GenericsUtil.getLeastUpperBound(lowerBound, type, typeParameter.getManager());
                if (lowerBound == null) return getFailedInferenceConstraint(typeParameter);
              }
            }
            break;
          case SUBTYPE:
            if (upperBound == PsiType.NULL) {
              upperBound = type;
            }
        }
      }
    }

    if (wildcardToCapture != null) {
      if (lowerBound != PsiType.NULL) {
        if (!wildcardToCapture.isAssignableFrom(lowerBound)) return getFailedInferenceConstraint(typeParameter);
        lowerBound = GenericsUtil.getLeastUpperBound(lowerBound, wildcardToCapture, typeParameter.getManager());
      } else {
        if (upperBound != PsiType.NULL && !upperBound.isAssignableFrom(wildcardToCapture)) return getFailedInferenceConstraint(typeParameter);
        return new Pair<PsiType, ConstraintType>(wildcardToCapture, ConstraintType.EQUALS);
      }
    }

    if (lowerBound != PsiType.NULL) return new Pair<PsiType, ConstraintType>(lowerBound, ConstraintType.EQUALS);

    final Pair<PsiType, ConstraintType> constraint =
      inferMethodTypeParameterFromParent(typeParameter, partialSubstitutor, parent, forCompletion);
    if (constraint != null) {
      if (constraint.getSecond() != ConstraintType.SUBTYPE) {
        return constraint;
      }

      if (upperBound != PsiType.NULL) {
        return new Pair<PsiType, ConstraintType>(upperBound, ConstraintType.SUBTYPE);
      }

      return constraint;
    }

    if (upperBound != PsiType.NULL) return new Pair<PsiType, ConstraintType>(upperBound, ConstraintType.SUBTYPE);
    return null;
  }

  private static Pair<PsiType, ConstraintType> getFailedInferenceConstraint(final PsiTypeParameter typeParameter) {
    return new Pair<PsiType, ConstraintType>(typeParameter.getManager().getElementFactory().createType(typeParameter), ConstraintType.EQUALS);
  }

  public PsiType inferTypeForMethodTypeParameter(final PsiTypeParameter typeParameter,
                                                 final PsiParameter[] parameters,
                                                 PsiExpression[] arguments,
                                                 PsiSubstitutor partialSubstitutor,
                                                 PsiElement parent,
                                                 final boolean forCompletion) {

    final Pair<PsiType, ConstraintType> constraint =
      inferTypeForMethodTypeParameterInner(typeParameter, parameters, arguments, partialSubstitutor, parent, forCompletion);
    if (constraint == null) return PsiType.NULL;
    return constraint.getFirst();
  }

  public PsiSubstitutor inferTypeArguments(PsiTypeParameter[] typeParameters,
                                           PsiParameter[] parameters,
                                           PsiExpression[] arguments,
                                           PsiSubstitutor partialSubstitutor,
                                           PsiElement parent,
                                           boolean forCompletion) {
    PsiType[] substitutions = new PsiType[typeParameters.length];
    //noinspection unchecked
    Pair<PsiType, ConstraintType>[] constraints = new Pair[typeParameters.length];
    for (int i = 0; i < typeParameters.length; i++) {
      final Pair<PsiType, ConstraintType> constraint =
        inferTypeForMethodTypeParameterInner(typeParameters[i], parameters, arguments, partialSubstitutor, parent, forCompletion);
      constraints[i] = constraint;
      if (constraint != null && constraint.getSecond() != ConstraintType.SUBTYPE) {
        substitutions[i] = constraint.getFirst();
      }
    }

    final LanguageLevel languageLevel = PsiUtil.getLanguageLevel(parent);
    final PsiManager manager = parent.getManager();
    for (int i = 0; i < typeParameters.length; i++) {
      PsiTypeParameter typeParameter = typeParameters[i];
      if (substitutions[i] == null) {
        PsiType substitutionFromBounds = PsiType.NULL;
        OtherParameters:
        for (int j = 0; j < typeParameters.length; j++) {
          if (i != j) {
            PsiTypeParameter other = typeParameters[j];
            final PsiType otherSubstitution = substitutions[j];
            if (otherSubstitution == null) continue;
            final PsiClassType[] bounds = other.getExtendsListTypes();
            for (PsiClassType bound : bounds) {
              final PsiType substitutedBound = partialSubstitutor.substitute(bound);
              final Pair<PsiType, ConstraintType> currentConstraint =
                getSubstitutionForTypeParameterConstraint(typeParameter, substitutedBound, otherSubstitution, true, languageLevel);
              if (currentConstraint == null) continue;
              final PsiType currentSubstitution = currentConstraint.getFirst();
              final ConstraintType currentConstraintType = currentConstraint.getSecond();
              if (currentConstraintType == ConstraintType.EQUALS) {
                substitutionFromBounds = currentSubstitution;
                break OtherParameters;
              }
              else if (currentConstraintType == ConstraintType.SUPERTYPE) {
                if (substitutionFromBounds == PsiType.NULL) {
                  substitutionFromBounds = currentSubstitution;
                }
                else {
                  substitutionFromBounds = GenericsUtil.getLeastUpperBound(substitutionFromBounds, currentSubstitution, manager);
                }
              }
            }

          }
        }

        if (substitutionFromBounds != PsiType.NULL) substitutions[i] = substitutionFromBounds;
      }
    }

    for (int i = 0; i < typeParameters.length; i++) {
      PsiTypeParameter typeParameter = typeParameters[i];
      PsiType substitution = substitutions[i];
      final Pair<PsiType, ConstraintType> constraint = constraints[i];
      if (substitution == null && constraint != null) {
        substitution = constraint.getFirst();
      }

      if (substitution == null) {
        return createRawSubstitutor(partialSubstitutor, typeParameters);
      }
      else if (substitution != PsiType.NULL) {
        partialSubstitutor = partialSubstitutor.put(typeParameter, substitution);
      }
    }
    return partialSubstitutor;
  }

  @NotNull
  public PsiSubstitutor inferTypeArguments(PsiTypeParameter[] typeParameters, PsiType[] leftTypes, PsiType[] rightTypes,
                                           final LanguageLevel languageLevel) {
    if (leftTypes.length != rightTypes.length) throw new IllegalArgumentException("Types must be of the same length");
    PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
    for (PsiTypeParameter typeParameter : typeParameters) {
      PsiType substitution = null;
      for (int i1 = 0; i1 < leftTypes.length; i1++) {
        PsiType leftType = leftTypes[i1];
        PsiType rightType = rightTypes[i1];
        final Pair<PsiType, ConstraintType> constraint =
          getSubstitutionForTypeParameterConstraint(typeParameter, leftType, rightType, true, languageLevel);
        if (constraint != null) {
          final ConstraintType constraintType = constraint.getSecond();
          final PsiType current = constraint.getFirst();
          if(constraintType == ConstraintType.EQUALS) {
            substitution = current;
            break;
          } else if (constraintType == ConstraintType.SUBTYPE) {
            if (substitution == null) {
              substitution = current;
            }
            else {
              substitution = GenericsUtil.getLeastUpperBound(substitution, current, typeParameter.getManager());
            }
          }
        }
      }

      if (substitution == null) substitution = TypeConversionUtil.typeParameterErasure(typeParameter);
      substitutor = substitutor.put(typeParameter, substitution);
    }
    return substitutor;
  }

  private static PsiSubstitutor createRawSubstitutor(PsiSubstitutor substitutor, PsiTypeParameter[] typeParameters) {
    for (PsiTypeParameter typeParameter : typeParameters) {
      substitutor = substitutor.put(typeParameter, null);
    }

    return substitutor;
  }

  @Nullable
  private static Pair<PsiType, ConstraintType> processArgType(PsiType arg, final ConstraintType constraintType,
                                                              final boolean captureWildcard) {
    if (arg instanceof PsiWildcardType && !captureWildcard) return FAILED_INFERENCE;
    if (arg != PsiType.NULL) return new Pair<PsiType, ConstraintType>(arg, constraintType);
    return null;
  }

  private Pair<PsiType, ConstraintType> inferMethodTypeParameterFromParent(final PsiTypeParameter typeParameter,
                                                     PsiSubstitutor substitutor,
                                                     PsiElement parent,
                                                     final boolean forCompletion) {
    PsiTypeParameterListOwner owner = typeParameter.getOwner();
    Pair<PsiType, ConstraintType> substitution = null;
    if (owner instanceof PsiMethod) {
      if (parent instanceof PsiMethodCallExpression) {
        PsiMethodCallExpression methodCall = (PsiMethodCallExpression)parent;
        substitution = inferMethodTypeParameterFromParent(methodCall.getParent(), methodCall, typeParameter, substitutor,
                                                          forCompletion);
      }
    }
    return substitution;
  }

  public PsiType getSubstitutionForTypeParameter(PsiTypeParameter typeParam,
                                                 PsiType param,
                                                 PsiType arg,
                                                 boolean isContraVariantPosition,
                                                 final LanguageLevel languageLevel) {
    final Pair<PsiType, ConstraintType> constraint = getSubstitutionForTypeParameterConstraint(typeParam, param, arg, isContraVariantPosition,
                                                                                               languageLevel);
    return constraint == null ? PsiType.NULL : constraint.getFirst();
  }

  @Nullable
  private static Pair<PsiType, ConstraintType> getSubstitutionForTypeParameterConstraint(PsiTypeParameter typeParam,
                                                                                        PsiType param,
                                                                                        PsiType arg,
                                                                                        boolean isContraVariantPosition,
                                                                                        final LanguageLevel languageLevel) {
    if (param instanceof PsiArrayType && arg instanceof PsiArrayType) {
      return getSubstitutionForTypeParameterConstraint(typeParam, ((PsiArrayType)param).getComponentType(), ((PsiArrayType)arg).getComponentType(),
                                             isContraVariantPosition, languageLevel);
    }

    if (param instanceof PsiClassType) {
      PsiManager manager = typeParam.getManager();
      if (arg instanceof PsiPrimitiveType) {
        arg = ((PsiPrimitiveType)arg).getBoxedType(typeParam);
        if (arg == null) return null;
      }

      JavaResolveResult paramResult = ((PsiClassType)param).resolveGenerics();
      PsiClass paramClass = (PsiClass)paramResult.getElement();
      if (typeParam == paramClass) {
        return arg == null || arg.getDeepComponentType() instanceof PsiPrimitiveType || arg instanceof PsiIntersectionType ||
               PsiUtil.resolveClassInType(arg) != null ? new Pair<PsiType, ConstraintType> (arg, ConstraintType.SUPERTYPE) : null;
      }
      if (paramClass == null) return null;

      if (arg instanceof PsiClassType) {
        JavaResolveResult argResult = ((PsiClassType)arg).resolveGenerics();
        PsiClass argClass = (PsiClass)argResult.getElement();
        if (argClass == null) return null;

        PsiElementFactory factory = manager.getElementFactory();
        PsiType patternType = factory.createType(typeParam);
        if (isContraVariantPosition) {
          PsiSubstitutor substitutor = TypeConversionUtil.getClassSubstitutor(paramClass, argClass, argResult.getSubstitutor());
          if (substitutor == null) return null;
          arg = factory.createType(paramClass, substitutor, languageLevel);
        }
        else {
          PsiSubstitutor substitutor = TypeConversionUtil.getClassSubstitutor(argClass, paramClass, paramResult.getSubstitutor());
          if (substitutor == null) return null;
          param = factory.createType(argClass, substitutor, languageLevel);
        }

        return getSubstitutionForTypeParameterInner(param, arg, patternType, ConstraintType.SUPERTYPE, 0);
      }
    }

    return null;
  }

  private enum ConstraintType {
    EQUALS,
    SUBTYPE,
    SUPERTYPE
  }

  //represents the result of failed type inference: in case we failed inferring from parameters, do not perform inference from context
  private static final Pair<PsiType, ConstraintType> FAILED_INFERENCE = new Pair<PsiType, ConstraintType>(PsiType.NULL, ConstraintType.EQUALS);

  @Nullable
  private static Pair<PsiType, ConstraintType> getSubstitutionForTypeParameterInner(PsiType param,
                                                                                    PsiType arg,
                                                                                    PsiType patternType,
                                                                                    final ConstraintType constraintType,
                                                                                    final int depth) {
    if (arg instanceof PsiCapturedWildcardType) arg = ((PsiCapturedWildcardType)arg).getWildcard(); //reopen

    if (patternType.equals(param)) {
      return processArgType(arg, constraintType, depth < 2);
    }

    if (param instanceof PsiWildcardType) {
      final PsiWildcardType wildcardParam = (PsiWildcardType)param;
      final PsiType paramBound = wildcardParam.getBound();
      if (paramBound == null) return null;
      ConstraintType constrType = wildcardParam.isExtends() ? ConstraintType.SUPERTYPE : ConstraintType.SUBTYPE;
      if (arg instanceof PsiWildcardType) {
        if (((PsiWildcardType)arg).isExtends() == wildcardParam.isExtends()) {
          Pair<PsiType, ConstraintType> res = getSubstitutionForTypeParameterInner(paramBound, ((PsiWildcardType)arg).getBound(),
                                                                                   patternType, constrType, depth);
          if (res != null) return res;
        }
      }
      else if (patternType.equals(paramBound)) {
        Pair<PsiType, ConstraintType> res = getSubstitutionForTypeParameterInner(paramBound, arg,
                                                                                   patternType, constrType, depth);
        if (res != null) return res;
      }
      else if (paramBound instanceof PsiArrayType && arg instanceof PsiArrayType) {
        Pair<PsiType, ConstraintType> res = getSubstitutionForTypeParameterInner(((PsiArrayType) paramBound).getComponentType(),
                                                                                 ((PsiArrayType) arg).getComponentType(),
                                                                                 patternType, constrType, depth);
        if (res != null) return res;
      }
      else if (paramBound instanceof PsiClassType && arg instanceof PsiClassType) {
        final PsiClassType.ClassResolveResult boundResult = ((PsiClassType)paramBound).resolveGenerics();
        final PsiClass boundClass = boundResult.getElement();
        if (boundClass != null) {
          final PsiClassType.ClassResolveResult argResult = ((PsiClassType)arg).resolveGenerics();
          final PsiClass argClass = argResult.getElement();
          if (argClass != null) {
            if (wildcardParam.isExtends()) {
              PsiSubstitutor superSubstitutor = TypeConversionUtil.getClassSubstitutor(boundClass, argClass, argResult.getSubstitutor());
              if (superSubstitutor != null) {
                final Iterator<PsiTypeParameter> iterator = PsiUtil.typeParametersIterator(boundClass);
                while (iterator.hasNext()) {
                  final PsiTypeParameter typeParameter = iterator.next();
                  PsiType substituted = superSubstitutor.substitute(typeParameter);
                  if (substituted != null) {
                    Pair<PsiType, ConstraintType> res = getSubstitutionForTypeParameterInner(
                      boundResult.getSubstitutor().substitute(typeParameter), substituted, patternType, ConstraintType.EQUALS, depth + 1);
                    if (res != null) return res;
                  }
                }
              }
            }
            else {
              PsiSubstitutor superSubstitutor = TypeConversionUtil.getClassSubstitutor(argClass, boundClass, boundResult.getSubstitutor());
              if (superSubstitutor != null) {
                final Iterator<PsiTypeParameter> iterator = PsiUtil.typeParametersIterator(argClass);
                while (iterator.hasNext()) {
                  final PsiTypeParameter typeParameter = iterator.next();
                  PsiType substituted = argResult.getSubstitutor().substitute(typeParameter);
                  if (substituted != null) {
                    Pair<PsiType, ConstraintType> res = getSubstitutionForTypeParameterInner(
                      superSubstitutor.substitute(typeParameter), substituted, patternType, ConstraintType.EQUALS, depth + 1);
                    if (res != null) return res;
                  }
                }
              }
            }
          }
        }
      }
    }

    if (param instanceof PsiArrayType && arg instanceof PsiArrayType) {
      return getSubstitutionForTypeParameterInner(((PsiArrayType)param).getComponentType(), ((PsiArrayType)arg).getComponentType(),
                                                  patternType, constraintType, depth);
    }

    if (param instanceof PsiClassType && arg instanceof PsiClassType) {
      PsiClassType.ClassResolveResult paramResult = ((PsiClassType)param).resolveGenerics();
      PsiClass paramClass = paramResult.getElement();
      if (paramClass == null) return null;

      PsiClassType.ClassResolveResult argResult = ((PsiClassType)arg).resolveGenerics();
      PsiClass argClass = argResult.getElement();
      if (argClass != paramClass) return null;

      Pair<PsiType,ConstraintType> wildcardCaptured = null;
      final Iterator<PsiTypeParameter> iterator = PsiUtil.typeParametersIterator(paramClass);
      while(iterator.hasNext()) {
        final PsiTypeParameter typeParameter = iterator.next();
        PsiType paramType = paramResult.getSubstitutor().substitute(typeParameter);
        PsiType argType = argResult.getSubstitutor().substituteWithBoundsPromotion(typeParameter);

        Pair<PsiType,ConstraintType> res = getSubstitutionForTypeParameterInner(paramType, argType, patternType, ConstraintType.EQUALS, depth + 1);

        if (res != null) {
          PsiType type = res.getFirst();
          if (!(type instanceof PsiWildcardType)) return res;
          if (wildcardCaptured != null) return FAILED_INFERENCE;
          wildcardCaptured = res;
        }
      }

      return wildcardCaptured;
    }

    return null;
  }

  private Pair<PsiType, ConstraintType> inferMethodTypeParameterFromParent(PsiElement parent,
                                                     PsiMethodCallExpression methodCall,
                                                     final PsiTypeParameter typeParameter,
                                                     PsiSubstitutor substitutor,
                                                     final boolean forCompletion) {
    PsiType expectedType = null;

    if (parent instanceof PsiVariable && methodCall.equals(((PsiVariable)parent).getInitializer())) {
      expectedType = ((PsiVariable)parent).getType();
    }
    else if (parent instanceof PsiAssignmentExpression && methodCall.equals(((PsiAssignmentExpression)parent).getRExpression())) {
      expectedType = ((PsiAssignmentExpression)parent).getLExpression().getType();
    }
    else if (parent instanceof PsiReturnStatement) {
      PsiMethod method = PsiTreeUtil.getParentOfType(parent, PsiMethod.class);
      if (method != null) {
        expectedType = method.getReturnType();
      }
    }
    else if (parent instanceof PsiExpressionList && forCompletion) {
      final PsiElement pParent = parent.getParent();
      if (pParent instanceof PsiCallExpression && parent.equals(((PsiCallExpression)pParent).getArgumentList())) {
        final Pair<PsiType, ConstraintType> constraint =
          inferTypeForCompletionFromCallContext(methodCall, (PsiExpressionList)parent, (PsiCallExpression)pParent, typeParameter);
        if (constraint != null) return constraint;
      }
    }

    final Pair<PsiType, ConstraintType> result;
    final PsiManager manager = typeParameter.getManager();
    final GlobalSearchScope scope = parent.getResolveScope();
    if (expectedType == null) {
      expectedType = forCompletion ?
             PsiType.NULL :
             PsiType.getJavaLangObject(manager, scope);
    }

    PsiType returnType = ((PsiMethod)typeParameter.getOwner()).getReturnType();
    final Pair<PsiType, ConstraintType> constraint =
      getSubstitutionForTypeParameterConstraint(typeParameter, returnType, expectedType, false, PsiUtil.getLanguageLevel(parent));

    if (constraint == null) {
      final PsiSubstitutor finalSubstitutor = substitutor.put(typeParameter, null);
      PsiType superType = finalSubstitutor.substitute(typeParameter.getSuperTypes()[0]);
      if (superType == null) superType = PsiType.getJavaLangObject(manager, scope);
      if (forCompletion && !(superType instanceof PsiWildcardType)) {
        result = new Pair<PsiType, ConstraintType>(PsiWildcardType.createExtends(manager, superType), ConstraintType.EQUALS);
      }
      else {
        result = new Pair<PsiType, ConstraintType>(superType, ConstraintType.SUBTYPE);
      }
    } else {
      PsiType guess = constraint.getFirst();
      if (forCompletion && !(guess instanceof PsiWildcardType)) guess = PsiWildcardType.createExtends(manager, guess);

      //The following code is the result of deep thought, do not shit it out before discussing with [ven]
      if (returnType instanceof PsiClassType && typeParameter.equals(((PsiClassType)returnType).resolve())) {
        PsiClassType[] extendsTypes = typeParameter.getExtendsListTypes();
        PsiSubstitutor newSubstitutor = substitutor.put(typeParameter, guess);
        for (PsiClassType extendsType1 : extendsTypes) {
          PsiType extendsType = newSubstitutor.substitute(extendsType1);
          if (!extendsType.isAssignableFrom(guess)) {
            if (guess.isAssignableFrom(extendsType)) {
              guess = extendsType;
              newSubstitutor = substitutor.put(typeParameter, guess);
            }
            else {
              break;
            }
          }
        }
      }

      result = new Pair<PsiType, ConstraintType>(guess, constraint.getSecond());
    }
    return result;
  }

  private ThreadLocal<List<PsiMethodCallExpression>> myBlockedForInferenceMethodCalls = new ThreadLocal<List<PsiMethodCallExpression>>() {
    protected List<PsiMethodCallExpression> initialValue() {
      return new ArrayList<PsiMethodCallExpression>(2);
    }
  };

  private Pair<PsiType, ConstraintType> inferTypeForCompletionFromCallContext(final PsiMethodCallExpression innerMethodCall,
                                                                                     final PsiExpressionList expressionList,
                                                                                     final PsiCallExpression contextCall,
                                                                                     final PsiTypeParameter typeParameter) {
    final MethodCandidatesProcessor processor = new MethodCandidatesProcessor(contextCall);
    try {
      //can't call resolve() since it obtains full substitution, that may result in infinite recursion
      PsiScopesUtil.setupAndRunProcessor(processor, contextCall, false);
      int i = ArrayUtil.find(expressionList.getExpressions(), innerMethodCall);
      assert i >= 0;
      final JavaResolveResult[] results = processor.getResult();
      final PsiType innerReturnType = ((PsiMethod)typeParameter.getOwner()).getReturnType();
      for (JavaResolveResult result : results) {
        final PsiElement element = result.getElement();
        if (element instanceof PsiMethod) {
          final PsiMethod method = (PsiMethod)element;
          final PsiParameter[] parameters = method.getParameterList().getParameters();
          PsiParameter parameter = null;
          if (parameters.length > i) {
            parameter = parameters[i];
          } else if (method.isVarArgs()) {
            parameter = parameters[parameters.length - 1];
          }
          if (parameter != null) {
            //prevent infinite recursion
            myBlockedForInferenceMethodCalls.get().add(innerMethodCall);
            PsiType type = result.getSubstitutor().substitute(parameter.getType());
            myBlockedForInferenceMethodCalls.get().remove(innerMethodCall);
            final Pair<PsiType, ConstraintType> constraint =
              getSubstitutionForTypeParameterConstraint(typeParameter, innerReturnType, type, false, PsiUtil.getLanguageLevel(innerMethodCall));
            if (constraint != null) return constraint;
          }
        }
      }
    }
    catch (MethodProcessorSetupFailedException ev) {
      return null;
    }

    return null;
  }
}
